/*
 * Copyright 2020 Manuel Wrage
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivianuu.injekt.compiler.transform.factory

import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.InjektSymbols
import com.ivianuu.injekt.compiler.transform.InjektDeclarationIrBuilder
import com.ivianuu.injekt.compiler.typeArguments
import com.ivianuu.injekt.compiler.typeOrFail
import com.ivianuu.injekt.compiler.withNoArgAnnotations
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.name.FqName

class FactoryExpressions(
    private val graph: Graph,
    private val pluginContext: IrPluginContext,
    private val symbols: InjektSymbols,
    private val members: FactoryMembers,
    private val parent: FactoryExpressions?,
    private val factory: AbstractFactory
) {

    private val bindingExpressions = mutableMapOf<BindingRequest, FactoryExpression>()

    private val requirementExpressions = mutableMapOf<RequirementNode, FactoryExpression>()

    private val pair = pluginContext.referenceClass(FqName("kotlin.Pair"))!!
        .owner

    fun getRequirementExpression(node: RequirementNode): FactoryExpression {
        requirementExpressions[node]?.let { return it }
        val expression = members.cachedValue(node.key) {
            node.accessor(this)
        }
        requirementExpressions[node] = expression
        return expression
    }

    fun getBindingExpression(request: BindingRequest): FactoryExpression {
        val binding = graph.getBinding(request)

        if (binding.owner != factory) {
            return parent?.getBindingExpression(request)!!
        }

        bindingExpressions[request]?.let { return it }

        val expression = when (request.requestType) {
            RequestType.Instance -> {
                if (bindingExpressions.containsKey(
                        BindingRequest(
                            binding.key,
                            binding.origin,
                            RequestType.Provider
                        )
                    )
                ) {
                    if (request.key.type.isFunction() &&
                        request.key.type.hasAnnotation(InjektFqNames.Provider)
                    ) {
                        getBindingExpression(
                            BindingRequest(
                                binding.key,
                                binding.origin,
                                RequestType.Provider
                            )
                        )
                    } else {
                        invokeProviderInstanceExpression(binding)
                    }
                } else {
                    when (binding) {
                        is AssistedProvisionBindingNode -> instanceExpressionForAssistedProvision(
                            binding
                        )
                        is ChildFactoryBindingNode -> instanceExpressionForChildFactory(binding)
                        is DelegateBindingNode -> instanceExpressionForDelegate(binding)
                        is DependencyBindingNode -> instanceExpressionForDependency(binding)
                        is FactoryImplementationBindingNode -> instanceExpressionForFactoryImplementation(
                            binding
                        )
                        is InstanceBindingNode -> instanceExpressionForInstance(binding)
                        is LazyBindingNode -> instanceExpressionForLazy(binding)
                        is MapBindingNode -> instanceExpressionForMap(binding)
                        is MembersInjectorBindingNode -> instanceExpressionForMembersInjector(
                            binding
                        )
                        is NullBindingNode -> instanceExpressionForNull(binding)
                        is ProviderBindingNode -> instanceExpressionForProvider(binding)
                        is ProvisionBindingNode -> instanceExpressionForProvision(binding)
                        is SetBindingNode -> instanceExpressionForSet(binding)
                    }
                }
            }
            RequestType.Provider -> {
                when (binding) {
                    is AssistedProvisionBindingNode -> providerExpressionForAssistedProvision(
                        binding
                    )
                    is ChildFactoryBindingNode -> providerExpressionForChildFactory(binding)
                    is DelegateBindingNode -> providerExpressionForDelegate(binding)
                    is DependencyBindingNode -> providerExpressionForDependency(binding)
                    is FactoryImplementationBindingNode -> providerExpressionForFactoryImplementation(
                        binding
                    )
                    is InstanceBindingNode -> providerExpressionForInstance(binding)
                    is LazyBindingNode -> providerExpressionForLazy(binding)
                    is MapBindingNode -> providerExpressionForMap(binding)
                    is MembersInjectorBindingNode -> providerExpressionForMembersInjector(binding)
                    is NullBindingNode -> providerExpressionForNull(binding)
                    is ProviderBindingNode -> providerExpressionForProvider(binding)
                    is ProvisionBindingNode -> providerExpressionForProvision(binding)
                    is SetBindingNode -> providerExpressionForSet(binding)
                }
            }
        }

        bindingExpressions[request] = expression
        return expression
    }

    private fun instanceExpressionForAssistedProvision(binding: AssistedProvisionBindingNode): FactoryExpression {
        val (assistedParameters, nonAssistedParameters) = binding.parameters
            .partition { it.assisted }

        val dependencyExpressions = binding.dependencies
            .map { getBindingExpression(it) }

        return {
            InjektDeclarationIrBuilder(pluginContext, factory.moduleClass.symbol)
                .irLambda(
                    pluginContext.irBuiltIns.function(
                        assistedParameters
                            .size
                    ).typeWith(
                        assistedParameters
                            .map { it.type } +
                                binding.key.type
                    )
                ) { lambda ->
                    val provisionFunctionExpression = binding.provisionFunctionExpression(this)
                    +irReturn(
                        irCall(provisionFunctionExpression.type
                            .classOrNull!!
                            .functions
                            .single { it.owner.name.asString() == "invoke" }
                        ).apply {
                            dispatchReceiver = provisionFunctionExpression

                            binding.parameters.forEachIndexed { index, parameter ->
                                if (parameter.assisted) {
                                    putValueArgument(
                                        index,
                                        irGet(
                                            lambda.valueParameters[assistedParameters.indexOf(
                                                parameter
                                            )]
                                        )
                                    )
                                } else {
                                    putValueArgument(
                                        index,
                                        dependencyExpressions[nonAssistedParameters.indexOf(
                                            parameter
                                        )]()
                                    )
                                }
                            }
                        }
                    )
                }
        }
    }

    private fun instanceExpressionForChildFactory(binding: ChildFactoryBindingNode): FactoryExpression {
        return invokeProviderInstanceExpression(binding)
    }

    private fun instanceExpressionForDelegate(binding: DelegateBindingNode): FactoryExpression =
        getBindingExpression(binding.dependencies.single())

    private fun instanceExpressionForDependency(binding: DependencyBindingNode): FactoryExpression {
        val dependencyExpression = getRequirementExpression(binding.requirementNode)
        val expression: FactoryExpression = bindingExpression@{
            irCall(binding.function).apply {
                dispatchReceiver = dependencyExpression()
            }
        }

        return expression.wrapInFunction(binding.key)
    }

    private fun instanceExpressionForFactoryImplementation(
        binding: FactoryImplementationBindingNode
    ): FactoryExpression {
        return invokeProviderInstanceExpression(binding)
    }

    private fun instanceExpressionForInstance(binding: InstanceBindingNode): FactoryExpression {
        return getRequirementExpression(binding.requirementNode)
    }

    private fun instanceExpressionForLazy(binding: LazyBindingNode): FactoryExpression {
        return {
            doubleCheck(
                getBindingExpression(
                    binding.dependencies.single().copy(
                        requestType = RequestType.Provider
                    )
                )()
            )
        }
    }

    private fun instanceExpressionForMap(binding: MapBindingNode): FactoryExpression {
        val entryExpressions = binding.entries
            .map { (key, entryValue) ->
                val entryValueExpression = getBindingExpression(
                    when {
                        binding.valueKey.type.isFunction() &&
                                binding.valueKey.type.hasAnnotation(InjektFqNames.Provider) ->
                            entryValue.copy(requestType = RequestType.Provider)
                        binding.valueKey.type.isFunction() &&
                                binding.valueKey.type.hasAnnotation(InjektFqNames.Lazy) -> {
                            BindingRequest(
                                pluginContext.irBuiltIns.function(0)
                                    .typeWith(entryValue.key.type)
                                    .withNoArgAnnotations(pluginContext, listOf(InjektFqNames.Lazy))
                                    .asKey(),
                                entryValue.requestOrigin
                            )
                        }
                        else -> entryValue
                    }
                )
                val pairExpression: FactoryExpression = pairExpression@{
                    irCall(pair.constructors.single()).apply {
                        putTypeArgument(0, binding.keyKey.type)
                        putTypeArgument(1, binding.valueKey.type)

                        putValueArgument(
                            0,
                            with(key) { asExpression() }
                        )
                        putValueArgument(
                            1,
                            entryValueExpression()
                        )
                    }
                }
                pairExpression
            }

        val expression: FactoryExpression = bindingExpression@{
            when (entryExpressions.size) {
                0 -> {
                    irCall(
                        pluginContext.referenceFunctions(FqName("kotlin.collections.emptyMap"))
                            .single()
                    ).apply {
                        putTypeArgument(0, binding.keyKey.type)
                        putTypeArgument(1, binding.valueKey.type)
                    }
                }
                1 -> {
                    irCall(
                        pluginContext.referenceFunctions(FqName("kotlin.collections.mapOf"))
                            .single {
                                it.owner.valueParameters.singleOrNull()?.isVararg == false
                            }
                    ).apply {
                        putTypeArgument(0, binding.keyKey.type)
                        putTypeArgument(1, binding.valueKey.type)
                        putValueArgument(
                            0,
                            entryExpressions.single()(),
                        )
                    }
                }
                else -> {
                    irCall(
                        pluginContext.referenceFunctions(FqName("kotlin.collections.mapOf"))
                            .single {
                                it.owner.valueParameters.singleOrNull()?.isVararg == true
                            }
                    ).apply {
                        putTypeArgument(0, binding.keyKey.type)
                        putTypeArgument(1, binding.valueKey.type)
                        putValueArgument(
                            0,
                            IrVarargImpl(
                                UNDEFINED_OFFSET,
                                UNDEFINED_OFFSET,
                                this@FactoryExpressions.pluginContext.irBuiltIns.arrayClass
                                    .typeWith(
                                        pair.typeWith(
                                            binding.keyKey.type,
                                            binding.valueKey.type
                                        )
                                    ),
                                pair.typeWith(
                                    binding.keyKey.type,
                                    binding.valueKey.type
                                ),
                                entryExpressions.map { it() }
                            ),
                        )
                    }
                }
            }
        }

        return expression.wrapInFunction(binding.key)
    }

    private fun instanceExpressionForMembersInjector(binding: MembersInjectorBindingNode): FactoryExpression {
        val dependencyExpressions = binding.dependencies
            .map { getBindingExpression(it) }
        return {
            InjektDeclarationIrBuilder(pluginContext, scope.scopeOwnerSymbol)
                .irLambda(binding.key.type) { lambda ->
                    if (binding.membersInjector != null) {
                        +irCall(binding.membersInjector).apply {
                            putValueArgument(0, irGet(lambda.valueParameters.single()))
                            dependencyExpressions.forEachIndexed { index, dependency ->
                                putValueArgument(
                                    index + 1,
                                    dependency()
                                )
                            }
                        }
                    }
                }
        }
    }

    private fun instanceExpressionForNull(binding: NullBindingNode): FactoryExpression {
        return { irNull() }
    }

    private fun instanceExpressionForProvider(binding: ProviderBindingNode): FactoryExpression {
        return getBindingExpression(
            BindingRequest(
                binding.key.type.typeArguments.single().typeOrFail.asKey(),
                binding.origin,
                RequestType.Provider
            )
        )
    }

    private fun instanceExpressionForProvision(binding: ProvisionBindingNode): FactoryExpression {
        val expression = if (binding.scoped) {
            invokeProviderInstanceExpression(binding)
        } else {
            val dependencies = binding.dependencies
                .map { getBindingExpression(it) }

            val expression: FactoryExpression = bindingExpression@{
                val provisionFunctionExpression = binding.provisionFunctionExpression(this)
                irCall(provisionFunctionExpression.type
                    .classOrNull!!
                    .functions
                    .single { it.owner.name.asString() == "invoke" }
                ).apply {
                    dispatchReceiver = provisionFunctionExpression
                    dependencies.forEachIndexed { index, dependency ->
                        putValueArgument(
                            index,
                            dependency()
                        )
                    }
                }
            }
            expression
        }

        return expression.wrapInFunction(binding.key)
    }

    private fun instanceExpressionForSet(binding: SetBindingNode): FactoryExpression {
        val elementExpressions = binding.dependencies
            .map { element ->
                getBindingExpression(
                    when {
                        binding.elementKey.type.isFunction() &&
                                binding.elementKey.type.hasAnnotation(InjektFqNames.Provider) ->
                            element.copy(requestType = RequestType.Provider)
                        binding.elementKey.type.isFunction() &&
                                binding.elementKey.type.hasAnnotation(InjektFqNames.Lazy) -> {
                            BindingRequest(
                                pluginContext.irBuiltIns.function(0)
                                    .typeWith(element.key.type)
                                    .withNoArgAnnotations(pluginContext, listOf(InjektFqNames.Lazy))
                                    .asKey(),
                                element.requestOrigin
                            )
                        }
                        else -> element
                    }
                )
            }

        val expression: FactoryExpression = bindingExpression@{
            when (elementExpressions.size) {
                0 -> {
                    irCall(
                        pluginContext.referenceFunctions(FqName("kotlin.collections.emptySet"))
                            .single()
                    ).apply {
                        putTypeArgument(0, binding.elementKey.type)
                    }
                }
                1 -> {
                    irCall(
                        pluginContext.referenceFunctions(FqName("kotlin.collections.setOf"))
                            .single {
                                it.owner.valueParameters.singleOrNull()?.isVararg == false
                            }
                    ).apply {
                        putTypeArgument(0, binding.elementKey.type)
                        putValueArgument(
                            0,
                            elementExpressions.single()()
                        )
                    }
                }
                else -> {
                    irCall(
                        pluginContext.referenceFunctions(FqName("kotlin.collections.setOf"))
                            .single {
                                it.owner.valueParameters.singleOrNull()?.isVararg == true
                            }
                    ).apply {
                        putTypeArgument(0, binding.elementKey.type)
                        putValueArgument(
                            0,
                            IrVarargImpl(
                                UNDEFINED_OFFSET,
                                UNDEFINED_OFFSET,
                                this@FactoryExpressions.pluginContext.irBuiltIns.arrayClass
                                    .typeWith(binding.elementKey.type),
                                binding.elementKey.type,
                                elementExpressions.map { it() }
                            )
                        )
                    }
                }
            }
        }

        return expression.wrapInFunction(binding.key)
    }

    private fun providerExpressionForAssistedProvision(binding: AssistedProvisionBindingNode): FactoryExpression {
        return cachedProviderExpression(binding.key) {
            instanceProvider(
                getBindingExpression(
                    BindingRequest(binding.key, binding.origin, RequestType.Instance)
                )()
            )
        }
    }

    private fun providerExpressionForChildFactory(binding: ChildFactoryBindingNode): FactoryExpression {
        return cachedProviderExpression(binding.key) {
            instanceProvider(binding.childFactoryExpression(this))
        }
    }

    private fun providerExpressionForDelegate(binding: DelegateBindingNode): FactoryExpression =
        getBindingExpression(binding.dependencies.single().copy(requestType = RequestType.Provider))

    private fun providerExpressionForDependency(binding: DependencyBindingNode): FactoryExpression {
        val dependencyExpression = getRequirementExpression(binding.requirementNode)
        return cachedProviderExpression(binding.key) providerFieldExpression@{
            InjektDeclarationIrBuilder(pluginContext, factory.moduleClass.symbol)
                .irLambda(
                    pluginContext.irBuiltIns.function(0)
                        .typeWith(binding.key.type)
                ) {
                    +irReturn(
                        irCall(binding.function).apply {
                            dispatchReceiver = dependencyExpression()
                        }
                    )
                }
        }
    }

    private fun providerExpressionForFactoryImplementation(
        binding: FactoryImplementationBindingNode
    ): FactoryExpression {
        factory as ImplFactory

        return cachedProviderExpression(binding.key) {
            irCall(symbols.lateinitFactory.constructors.single()).apply {
                putTypeArgument(0, factory.clazz.superTypes.single())
            }
        }.also { factory.factoryLateinitProvider = it }
    }

    private fun providerExpressionForInstance(binding: InstanceBindingNode): FactoryExpression {
        return cachedProviderExpression(binding.key) {
            instanceProvider(binding.requirementNode.accessor(this))
        }
    }

    private fun providerExpressionForLazy(binding: LazyBindingNode): FactoryExpression {
        val dependencyExpression = getBindingExpression(
            BindingRequest(
                binding.key.type.typeArguments.single().typeOrFail.asKey(),
                binding.origin,
                RequestType.Provider
            )
        )
        return cachedProviderExpression(binding.key) {
            irCall(
                symbols.providerOfLazy
                    .constructors
                    .single()
            ).apply {
                putValueArgument(0, dependencyExpression())
            }
        }
    }

    private fun providerExpressionForMap(binding: MapBindingNode): FactoryExpression {
        val entryExpressions = binding.entries
            .map { (key, entryValue) ->
                val entryValueExpression = getBindingExpression(
                    BindingRequest(
                        entryValue.key,
                        binding.origin,
                        RequestType.Provider
                    )
                )
                val pairExpression: FactoryExpression = pairExpression@{
                    irCall(pair.constructors.single()).apply {
                        putTypeArgument(0, binding.keyKey.type)
                        putTypeArgument(1, binding.valueKey.type)

                        putValueArgument(
                            0,
                            with(key) { asExpression() }
                        )
                        putValueArgument(
                            1,
                            entryValueExpression()
                        )
                    }
                }
                pairExpression
            }

        return cachedProviderExpression(binding.key) providerFieldExpression@{
            val mapFactoryCompanion = when {
                binding.valueKey.type.isFunction() &&
                        binding.valueKey.type.hasAnnotation(InjektFqNames.Provider) -> {
                    symbols.mapOfProviderFactory.owner.companionObject() as IrClass
                }
                binding.valueKey.type.isFunction() &&
                        binding.valueKey.type.hasAnnotation(InjektFqNames.Lazy) -> {
                    symbols.mapOfLazyFactory.owner.companionObject() as IrClass
                }
                else -> symbols.mapOfValueFactory.owner.companionObject() as IrClass
            }

            if (entryExpressions.isEmpty()) {
                irCall(
                    mapFactoryCompanion.functions
                        .single { it.name.asString() == "empty" }
                ).apply {
                    dispatchReceiver = irGetObject(mapFactoryCompanion.symbol)
                    putTypeArgument(0, binding.keyKey.type)
                    putTypeArgument(1, binding.valueKey.type)
                }
            } else {
                when (entryExpressions.size) {
                    1 -> {
                        val create = mapFactoryCompanion.functions
                            .single {
                                it.name.asString() == "create" &&
                                        !it.valueParameters.single().isVararg
                            }

                        irCall(create).apply {
                            dispatchReceiver = irGetObject(mapFactoryCompanion.symbol)
                            putTypeArgument(0, binding.keyKey.type)
                            putTypeArgument(1, binding.valueKey.type)
                            putValueArgument(
                                0,
                                entryExpressions.single()()
                            )
                        }
                    }
                    else -> {
                        val create = mapFactoryCompanion.functions
                            .single {
                                it.name.asString() == "create" &&
                                        it.valueParameters.single().isVararg
                            }

                        irCall(create).apply {
                            dispatchReceiver = irGetObject(mapFactoryCompanion.symbol)
                            putTypeArgument(0, binding.keyKey.type)
                            putTypeArgument(1, binding.valueKey.type)
                            putValueArgument(
                                0,
                                IrVarargImpl(
                                    UNDEFINED_OFFSET,
                                    UNDEFINED_OFFSET,
                                    this@FactoryExpressions.pluginContext.irBuiltIns.arrayClass
                                        .typeWith(
                                            pair.typeWith(
                                                binding.keyKey.type,
                                                binding.valueKey.type
                                            )
                                        ),
                                    pair.typeWith(
                                        binding.keyKey.type,
                                        binding.valueKey.type
                                    ),
                                    entryExpressions.map { it() }
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun providerExpressionForMembersInjector(binding: MembersInjectorBindingNode): FactoryExpression {
        val dependencyExpressions = binding.dependencies
            .map { getBindingExpression(it) }
        return {
            instanceProvider(
                InjektDeclarationIrBuilder(pluginContext, scope.scopeOwnerSymbol)
                    .irLambda(binding.key.type) { lambda ->
                        if (binding.membersInjector != null) {
                            +irCall(binding.membersInjector).apply {
                                putValueArgument(0, irGet(lambda.valueParameters.single()))
                                dependencyExpressions.forEachIndexed { index, dependency ->
                                    putValueArgument(
                                        index + 1,
                                        dependency()
                                    )
                                }
                            }
                        }
                    }
            )
        }
    }

    private fun providerExpressionForNull(binding: NullBindingNode): FactoryExpression {
        return { instanceProvider(irNull()) }
    }

    private fun providerExpressionForProvider(binding: ProviderBindingNode): FactoryExpression {
        return getBindingExpression(
            BindingRequest(
                binding.dependencies.single().key,
                binding.origin,
                RequestType.Provider
            )
        )
    }

    private fun providerExpressionForProvision(binding: ProvisionBindingNode): FactoryExpression {
        val dependencyExpressions = binding.dependencies
            .map { getBindingExpression(it) }

        return cachedProviderExpression(binding.key) providerFieldExpression@{
            val provisionFunctionExpression = binding.provisionFunctionExpression(this)

            val newProvider = if (provisionFunctionExpression.type.isFunction() &&
                provisionFunctionExpression.type.typeArguments.size == 1
            ) {
                provisionFunctionExpression
            } else {
                InjektDeclarationIrBuilder(pluginContext, factory.moduleClass.symbol)
                    .irLambda(
                        pluginContext.irBuiltIns.function(0)
                            .typeWith(binding.key.type)
                    ) {
                        +irReturn(
                            irCall(provisionFunctionExpression.type
                                .classOrNull!!
                                .functions
                                .single { it.owner.name.asString() == "invoke" }
                            ).apply {
                                dispatchReceiver = provisionFunctionExpression
                                dependencyExpressions.forEachIndexed { index, dependency ->
                                    putValueArgument(
                                        index,
                                        dependency()
                                    )
                                }
                            }
                        )
                    }
            }

            if (binding.scoped) {
                doubleCheck(newProvider)
            } else {
                newProvider
            }
        }
    }

    private fun providerExpressionForSet(binding: SetBindingNode): FactoryExpression {
        val elementExpressions = binding.dependencies
            .map {
                getBindingExpression(
                    it.copy(requestType = RequestType.Provider)
                )
            }

        return cachedProviderExpression(binding.key) providerFieldExpression@{
            val setFactoryCompanion = when {
                binding.elementKey.type.isFunction() &&
                        binding.elementKey.type.hasAnnotation(InjektFqNames.Provider) -> {
                    symbols.setOfProviderFactory.owner.companionObject() as IrClass
                }
                binding.elementKey.type.isFunction() &&
                        binding.elementKey.type.hasAnnotation(InjektFqNames.Lazy) -> {
                    symbols.setOfLazyFactory.owner.companionObject() as IrClass
                }
                else -> symbols.setOfValueFactory.owner.companionObject() as IrClass
            }

            if (elementExpressions.isEmpty()) {
                irCall(
                    setFactoryCompanion.functions
                        .single { it.name.asString() == "empty" }
                ).apply {
                    dispatchReceiver = irGetObject(setFactoryCompanion.symbol)
                    putTypeArgument(0, binding.elementKey.type)
                }
            } else {
                when (elementExpressions.size) {
                    1 -> {
                        val create = setFactoryCompanion.functions
                            .single {
                                it.name.asString() == "create" &&
                                        !it.valueParameters.single().isVararg
                            }

                        irCall(create).apply {
                            dispatchReceiver = irGetObject(setFactoryCompanion.symbol)
                            putTypeArgument(0, binding.elementKey.type)
                            putValueArgument(
                                0,
                                elementExpressions.single()()
                            )
                        }
                    }
                    else -> {
                        val create = setFactoryCompanion.functions
                            .single {
                                it.name.asString() == "create" &&
                                        it.valueParameters.single().isVararg
                            }

                        irCall(create).apply {
                            dispatchReceiver = irGetObject(setFactoryCompanion.symbol)
                            putTypeArgument(0, binding.elementKey.type)
                            putValueArgument(
                                0,
                                IrVarargImpl(
                                    UNDEFINED_OFFSET,
                                    UNDEFINED_OFFSET,
                                    this@FactoryExpressions.pluginContext.irBuiltIns.arrayClass
                                        .typeWith(
                                            pluginContext.irBuiltIns.function(0)
                                                .typeWith(binding.key.type)
                                        ),
                                    binding.elementKey.type,
                                    elementExpressions.map { it() }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun cachedProviderExpression(
        key: Key,
        providerInitializer: IrBuilderWithScope.() -> IrExpression
    ): FactoryExpression {
        return members.cachedValue(
            pluginContext.irBuiltIns.function(0)
                .typeWith(key.type)
                .withNoArgAnnotations(pluginContext, listOf(InjektFqNames.Provider))
                .asKey(),
            providerInitializer
        )
    }

    private fun IrBuilderWithScope.instanceProvider(instance: IrExpression): IrExpression {
        val instanceProviderCompanion = symbols.singleInstanceFactory.owner
            .companionObject() as IrClass
        return irCall(
            instanceProviderCompanion
                .declarations
                .filterIsInstance<IrFunction>()
                .single { it.name.asString() == "create" }
        ).apply {
            dispatchReceiver = irGetObject(instanceProviderCompanion.symbol)
            putValueArgument(0, instance)
        }
    }

    private fun IrBuilderWithScope.doubleCheck(provider: IrExpression): IrExpression {
        return irCall(
            symbols.doubleCheck
                .constructors
                .single()
        ).apply { putValueArgument(0, provider) }
    }

    private fun FactoryExpression.wrapInFunction(key: Key): FactoryExpression {
        val factoryExpression = this
        val function = members.getGetFunction(key) function@{ function ->
            factoryExpression()
        }
        return bindingExpression@{ irCall(function) }
    }

    private fun invokeProviderInstanceExpression(binding: BindingNode): FactoryExpression {
        val providerExpression = getBindingExpression(
            BindingRequest(
                binding.key,
                binding.origin,
                RequestType.Provider
            )
        )
        return bindingExpression@{
            irCall(
                pluginContext.irBuiltIns.function(0)
                    .functions
                    .single { it.owner.name.asString() == "invoke" }
            ).apply {
                dispatchReceiver = providerExpression()
            }
        }
    }

}

typealias FactoryExpression = IrBuilderWithScope.() -> IrExpression
