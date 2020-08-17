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

package com.ivianuu.injekt.compiler.transform.readercontext

import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.SimpleUniqueNameProvider
import com.ivianuu.injekt.compiler.addFile
import com.ivianuu.injekt.compiler.asNameId
import com.ivianuu.injekt.compiler.buildClass
import com.ivianuu.injekt.compiler.flatMapFix
import com.ivianuu.injekt.compiler.getAllClasses
import com.ivianuu.injekt.compiler.getAllFunctions
import com.ivianuu.injekt.compiler.getConstantFromAnnotationOrNull
import com.ivianuu.injekt.compiler.irLambda
import com.ivianuu.injekt.compiler.recordLookup
import com.ivianuu.injekt.compiler.transform.AbstractInjektTransformer
import com.ivianuu.injekt.compiler.transform.DeclarationGraph
import com.ivianuu.injekt.compiler.transform.InjektContext
import com.ivianuu.injekt.compiler.transform.implicit.ImplicitContextParamTransformer
import com.ivianuu.injekt.compiler.uniqueTypeName
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class ReaderContextImplTransformer(
    injektContext: InjektContext,
    private val declarationGraph: DeclarationGraph,
    private val implicitContextParamTransformer: ImplicitContextParamTransformer,
    private val initFile: IrFile
) : AbstractInjektTransformer(injektContext) {

    // todo do not expose internals
    val generatedContexts = mutableMapOf<IrClass, Set<IrClass>>()
    val inputsByContext = mutableMapOf<IrClass, IrClass>()

    override fun lower() {
        var lastParentsByIndex =
            emptyMap<IrClass, List<DeclarationGraph.ParentRunReaderContext>>()
        while (true) {
            val currentParentsByIndex =
                mutableMapOf<IrClass, List<DeclarationGraph.ParentRunReaderContext>>()
            val roundContextIndices = declarationGraph.contexts
                .filter { it.superTypes.first().classOrNull!!.owner !in generatedContexts }

            if (roundContextIndices.isEmpty()) break

            for (index in roundContextIndices) {
                val context = index.superTypes.first().classOrNull!!.owner
                if (index in generatedContexts) continue

                val parents = declarationGraph.getParentRunReaderContexts(context)

                val unimplementedParents = parents
                    .filter {
                        it is DeclarationGraph.ParentRunReaderContext.Unknown ||
                                (it is DeclarationGraph.ParentRunReaderContext.Known && (
                                        it.clazz != context &&
                                                it.clazz !in generatedContexts
                                        ))
                    }

                currentParentsByIndex[index] = parents

                // defer generation until all parent's are generated
                if (unimplementedParents.isNotEmpty() &&
                    currentParentsByIndex != lastParentsByIndex
                ) continue

                generatedContexts[context] = generateRunReaderContextWithFactory(
                    index,
                    parents
                        .filterIsInstance<DeclarationGraph.ParentRunReaderContext.Known>()
                        .map { it.clazz }
                        .filter { it != context }
                        .flatMapFix { generatedContexts[it]!! }
                        .map { inputsByContext[it]!! }
                        .toSet()
                )
            }

            if (lastParentsByIndex.isNotEmpty() &&
                lastParentsByIndex == currentParentsByIndex
            ) break

            lastParentsByIndex = currentParentsByIndex
        }
    }

    private fun generateRunReaderContextWithFactory(
        index: IrClass,
        allParentInputs: Set<IrClass>
    ): Set<IrClass> {
        val thisInputTypes = index.functions
            .single { it.name.asString() == "inputs" }
            .valueParameters
            .map { it.type }

        val fqName = FqName(
            index.getConstantFromAnnotationOrNull<String>(
                InjektFqNames.ContextDeclaration, 0
            )!!
        )
        val isChild = index.getConstantFromAnnotationOrNull<Boolean>(
            InjektFqNames.ContextDeclaration, 1
        )!!

        val file = injektContext.module.addFile(injektContext, fqName)

        val thisContext = index.superTypes[0].classOrNull!!.owner
        val callingContext = if (isChild) index.superTypes[1].classOrNull!!.owner else null

        val factory = buildClass {
            this.name = fqName.shortName()
            kind = ClassKind.OBJECT
            visibility = Visibilities.INTERNAL
        }.apply clazz@{
            parent = file
            createImplicitParameterDeclarationWithWrappedDescriptor()

            addConstructor {
                returnType = defaultType
                isPrimary = true
                visibility = Visibilities.PUBLIC
            }.apply {
                body = DeclarationIrBuilder(
                    injektContext,
                    symbol
                ).irBlockBody {
                    +irDelegatingConstructorCall(context.irBuiltIns.anyClass.constructors.single().owner)
                    +IrInstanceInitializerCallImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        this@clazz.symbol,
                        context.irBuiltIns.unitType
                    )
                }
            }
        }

        recordLookup(initFile, factory)

        val childrenNameProvider = SimpleUniqueNameProvider()

        val thisInputsByParentInputs = allParentInputs
            .takeIf { it.isNotEmpty() }
            .let { it ?: listOf(null) }
            .associateWith { parent ->
                generateInputs(
                    childrenNameProvider("Inputs".asNameId()),
                    parent,
                    thisInputTypes,
                    factory
                )
            }

        val contextImplsWithParentInputs: List<Pair<IrClass, IrClass?>> = allParentInputs
            .takeIf { it.isNotEmpty() }
            .let { it ?: listOf(null) }
            .map { parentInputs ->
                val contextImpl = generateRunReaderContext(
                    childrenNameProvider("Impl".asNameId()),
                    thisContext,
                    thisInputsByParentInputs[parentInputs]!!,
                    factory
                )

                inputsByContext[contextImpl] = thisInputsByParentInputs[parentInputs]!!

                contextImpl to parentInputs
            }

        factory.addFunction {
            name = "create".asNameId()
            returnType = thisContext.defaultType
        }.apply {
            dispatchReceiverParameter = factory.thisReceiver!!.copyTo(this)

            val parentValueParameter = if (isChild) {
                addValueParameter(
                    "parent",
                    callingContext!!.defaultType
                )
            } else null

            val inputNameProvider = SimpleUniqueNameProvider()
            thisInputTypes.forEach {
                addValueParameter(
                    inputNameProvider(it.uniqueTypeName()).asString(),
                    it
                )
            }

            body = DeclarationIrBuilder(injektContext, symbol).run {
                fun createContextImpl(contextImpl: IrClass, parent: IrClass?): IrExpression {
                    return if (contextImpl.isObject) {
                        irGetObject(contextImpl.symbol)
                    } else {
                        val contextImplInputs = contextImpl.constructors.single()
                            .valueParameters
                            .map { it.type }
                        irCall(contextImpl.constructors.single()).apply {
                            contextImplInputs.forEachIndexed { index, type ->
                                putValueArgument(
                                    index,
                                    valueParameters.firstOrNull {
                                        it.type == type
                                    }?.let { irGet(it) }
                                        ?: parent!!.getInputForType(type)
                                            .let { parentInput ->
                                                irCall(parentInput).apply {
                                                    dispatchReceiver = irAs(
                                                        irGet(parentValueParameter!!),
                                                        parent.defaultType
                                                    )
                                                }
                                            }
                                )
                            }
                        }
                    }
                }
                irExprBody(
                    if (contextImplsWithParentInputs.size == 1) {
                        val (contextImpl, parent) = contextImplsWithParentInputs.single()
                        createContextImpl(contextImpl, parent)
                    } else {
                        irWhen(
                            thisContext.defaultType,
                            contextImplsWithParentInputs.map { (contextImpl, parentInputs) ->
                                parentInputs!!
                                irBranch(
                                    irIs(irGet(parentValueParameter!!), parentInputs.defaultType),
                                    createContextImpl(contextImpl, parentInputs)
                                )
                            } + irElseBranch(
                                irCall(
                                    injektContext.referenceFunctions(
                                        FqName("kotlin.error")
                                    ).single()
                                ).apply {
                                    putValueArgument(
                                        0,
                                        irString("Unexpected parent")
                                    )
                                }
                            )
                        )
                    }
                )
            }
        }

        thisInputsByParentInputs.values.forEach { factory.addChild(it) }
        contextImplsWithParentInputs.forEach { factory.addChild(it.first) }

        file.addChild(factory)

        return contextImplsWithParentInputs
            .map { it.first }
            .toSet()
    }

    private fun IrClass.getInputForType(type: IrType): IrFunction {
        val processedSuperTypes = mutableSetOf<IrClass>()
        fun findIn(superType: IrClass): IrFunction? {
            if (superType in processedSuperTypes) return null
            processedSuperTypes += superType

            for (declaration in superType.declarations.toList()) {
                if (declaration !is IrFunction) continue
                if (declaration is IrConstructor) continue
                if (declaration.dispatchReceiverParameter?.type ==
                    injektContext.irBuiltIns.anyType
                ) continue
                if (declaration.returnType != type) continue
                return declaration
            }

            for (innerSuperType in superType.superTypes.map { it.classOrNull!!.owner }) {
                findIn(innerSuperType)?.let { return it }
            }

            return null
        }

        return findIn(this)!!
    }

    private fun generateInputs(
        name: Name,
        parent: IrClass?,
        inputTypes: List<IrType>,
        irParent: IrDeclarationParent
    ): IrClass {
        return buildClass {
            this.name = name
            kind = ClassKind.INTERFACE
            visibility = Visibilities.INTERNAL
        }.apply clazz@{
            this.parent = irParent
            if (parent != null) superTypes += parent.defaultType
            createImplicitParameterDeclarationWithWrappedDescriptor()

            val functionNameProvider = SimpleUniqueNameProvider()

            val parentInputTypes = parent?.getAllFunctions()
                ?.map { it.returnType }
                ?: emptyList()

            inputTypes
                .filterNot { it in parentInputTypes }
                .forEach { input ->
                    addFunction {
                        this.name = functionNameProvider(input.uniqueTypeName())
                        returnType = input
                        modality = Modality.ABSTRACT
                    }.apply {
                        dispatchReceiverParameter = thisReceiver!!.copyTo(this)
                        this.parent = this@clazz
                    }
                }
        }
    }

    private fun generateRunReaderContext(
        name: Name,
        thisContext: IrClass,
        inputs: IrClass,
        irParent: IrDeclarationParent
    ): IrClass {
        val inputTypes = inputs.getAllFunctions()
            .map { it.returnType }

        val contextImpl = buildClass {
            this.name = name
            visibility = Visibilities.INTERNAL
            if (inputTypes.isEmpty()) kind = ClassKind.OBJECT
        }.apply clazz@{
            parent = irParent
            createImplicitParameterDeclarationWithWrappedDescriptor()
        }

        val inputFieldNameProvider = SimpleUniqueNameProvider()
        val inputFields = inputTypes
            .map {
                contextImpl.addField(
                    inputFieldNameProvider(it.uniqueTypeName()),
                    it
                )
            }

        contextImpl.addConstructor {
            returnType = contextImpl.defaultType
            isPrimary = true
            visibility = Visibilities.PUBLIC
        }.apply {
            val inputValueParameters = inputFields.associateWith {
                addValueParameter(
                    it.name.asString(),
                    it.type
                )
            }

            body = DeclarationIrBuilder(
                injektContext,
                symbol
            ).irBlockBody {
                +irDelegatingConstructorCall(context.irBuiltIns.anyClass.constructors.single().owner)
                +IrInstanceInitializerCallImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    contextImpl.symbol,
                    context.irBuiltIns.unitType
                )
                inputValueParameters.forEach { (field, valueParameter) ->
                    +irSetField(
                        irGet(contextImpl.thisReceiver!!),
                        field,
                        irGet(valueParameter)
                    )
                }
            }
        }

        val graph = GivensGraph(
            declarationGraph = declarationGraph,
            contextImpl = contextImpl,
            inputs = inputFields,
            implicitContextParamTransformer = implicitContextParamTransformer
        )

        var firstRound = true
        val givenExpressions = mutableMapOf<Key, ContextExpression>()

        while (true) {
            val superTypes =
                (if (firstRound) listOf(
                    thisContext,
                    inputs
                ) + declarationGraph.getRunReaderContexts(thisContext)
                else graph.resolvedGivens.values
                    .flatMapFix { it.contexts }
                    .flatMapFix { it.getAllClasses() })
                    .flatMapFix { declarationGraph.getAllContextImplementations(it) }
                    .distinct()
                    .filter { it !in contextImpl.superTypes.map { it.classOrNull!!.owner } }

            if (superTypes.isEmpty()) break

            fun implement(superType: IrClass) {
                if (superType in contextImpl.superTypes.map { it.classOrNull!!.owner }) return
                contextImpl.superTypes += superType.defaultType
                recordLookup(contextImpl, superType)

                for (declaration in superType.declarations.toList()) {
                    if (declaration !is IrFunction) continue
                    if (declaration is IrConstructor) continue
                    if (declaration.dispatchReceiverParameter?.type ==
                        injektContext.irBuiltIns.anyType
                    ) continue
                    val existingDeclaration = contextImpl.functions.singleOrNull {
                        it.name == declaration.name
                    }
                    if (existingDeclaration != null) {
                        existingDeclaration.overriddenSymbols += declaration.symbol as IrSimpleFunctionSymbol
                        continue
                    }
                    val request =
                        GivenRequest(
                            declaration.returnType.asKey(),
                            null,
                            declaration.descriptor.fqNameSafe
                        )
                    givenExpressions.getOrPut(request.key) {
                        createGivenExpression(contextImpl, graph, request)
                    }
                }

                superType.superTypes
                    .map { it.classOrNull!!.owner }
                    .forEach { implement(it) }
            }

            superTypes.forEach { implement(it) }

            firstRound = false
        }

        return contextImpl
    }

    private fun createGivenExpression(
        context: IrClass,
        graph: GivensGraph,
        request: GivenRequest
    ): ContextExpression {
        val rawExpression = when (val node = graph.getGivenNode(request)) {
            is FunctionGivenNode -> givenExpression(node)
            is InstanceGivenNode -> inputExpression(node)
            is MapGivenNode -> mapExpression(node)
            is NullGivenNode -> nullExpression()
            is SetGivenNode -> setExpression(node)
        }

        val function = buildFun {
            this.name = request.key.type.uniqueTypeName()
            returnType = request.key.type
        }.apply {
            dispatchReceiverParameter = context.thisReceiver!!.copyTo(this)
            this.parent = context
            context.addChild(this)
            this.body =
                DeclarationIrBuilder(injektContext, symbol).run {
                    irExprBody(rawExpression(this) { irGet(dispatchReceiverParameter!!) })
                }
        }

        return {
            irCall(function).apply {
                dispatchReceiver = it()
            }
        }
    }

    private fun inputExpression(
        node: InstanceGivenNode
    ): ContextExpression = { irGetField(it(), node.inputField) }

    private fun mapExpression(node: MapGivenNode): ContextExpression {
        return { c ->
            irBlock {
                val tmpMap = irTemporary(
                    irCall(
                        injektContext.referenceFunctions(
                            FqName("kotlin.collections.mutableMapOf")
                        ).first { it.owner.valueParameters.isEmpty() })
                )
                val mapType = injektContext.referenceClass(
                    FqName("kotlin.collections.Map")
                )!!
                node.functions.forEach { function ->
                    +irCall(
                        tmpMap.type.classOrNull!!
                            .functions
                            .map { it.owner }
                            .single {
                                it.name.asString() == "putAll" &&
                                        it.valueParameters.singleOrNull()?.type?.classOrNull == mapType
                            }
                    ).apply {
                        dispatchReceiver = irGet(tmpMap)
                        putValueArgument(
                            0,
                            irCall(function.symbol).apply {
                                if (function.dispatchReceiverParameter != null)
                                    dispatchReceiver =
                                        irGetObject(function.dispatchReceiverParameter!!.type.classOrNull!!)
                                putValueArgument(valueArgumentsCount - 1, c())
                            }
                        )
                    }
                }

                +irGet(tmpMap)
            }
        }
    }

    private fun setExpression(node: SetGivenNode): ContextExpression {
        return { c ->
            irBlock {
                val tmpSet = irTemporary(
                    irCall(
                        injektContext.referenceFunctions(
                            FqName("kotlin.collections.mutableSetOf")
                        ).first { it.owner.valueParameters.isEmpty() })
                )
                val collectionType = injektContext.referenceClass(
                    FqName("kotlin.collections.Collection")
                )
                node.functions.forEach { function ->
                    +irCall(
                        tmpSet.type.classOrNull!!
                            .functions
                            .map { it.owner }
                            .single {
                                it.name.asString() == "addAll" &&
                                        it.valueParameters.singleOrNull()?.type?.classOrNull == collectionType
                            }
                    ).apply {
                        dispatchReceiver = irGet(tmpSet)
                        putValueArgument(
                            0,
                            irCall(function.symbol).apply {
                                if (function.dispatchReceiverParameter != null)
                                    dispatchReceiver =
                                        irGetObject(function.dispatchReceiverParameter!!.type.classOrNull!!)
                                putValueArgument(valueArgumentsCount - 1, c())
                            }
                        )
                    }
                }

                +irGet(tmpSet)
            }
        }
    }

    private fun nullExpression(): ContextExpression = { irNull() }

    private fun givenExpression(node: FunctionGivenNode): ContextExpression {
        return { c ->
            fun createExpression(parametersMap: Map<IrValueParameter, () -> IrExpression?>): IrExpression {
                val call = if (node.function is IrConstructor) {
                    IrConstructorCallImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        node.function.returnType,
                        node.function.symbol,
                        node.function.constructedClass.typeParameters.size,
                        node.function.typeParameters.size,
                        node.function.valueParameters.size
                    )
                } else {
                    IrCallImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        node.function.returnType,
                        node.function.symbol,
                        node.function.typeParameters.size,
                        node.function.valueParameters.size
                    )
                }
                call.apply {
                    if (node.function.dispatchReceiverParameter != null) {
                        dispatchReceiver = if (node.givenSetAccessExpression != null) {
                            node.givenSetAccessExpression!!(c)
                        } else {
                            irGetObject(
                                node.function.dispatchReceiverParameter!!.type.classOrNull!!
                            )
                        }
                    }

                    parametersMap.values.forEachIndexed { index, expression ->
                        putValueArgument(
                            index,
                            expression()
                        )
                    }

                    putValueArgument(valueArgumentsCount - 1, c())
                }

                return call /*if (binding.scopeComponent != null) {
                    irCall(
                        injektContext.injektSymbols.storage
                            .owner
                            .functions
                            .first { it.name.asString() == "scope" }
                    ).apply {
                        dispatchReceiver = createBindingExpression(
                            context,
                            graph,
                            BindingRequest(
                                key = binding.scopeComponent.defaultType.asKey(),
                                requestingKey = binding.key,
                                requestOrigin = binding.origin
                            )
                        )(c)
                        putValueArgument(
                            0,
                            irInt(binding.key.hashCode())
                        )
                        putValueArgument(
                            1,
                            irLambda(
                                injektContext.tmpFunction(0)
                                    .typeWith(binding.key.type)
                            ) { call }
                        )
                    }
                } else {*/

                //}
            }

            if (node.explicitParameters.isNotEmpty()) {
                irLambda(node.key.type) { function ->
                    val parametersMap = node.explicitParameters
                        .associateWith { parameter ->
                            {
                                irGet(
                                    function.valueParameters[parameter.index]
                                )
                            }
                        }

                    createExpression(parametersMap)
                }
            } else {
                createExpression(emptyMap())
            }
        }
    }

}

typealias ContextExpression = IrBuilderWithScope.(() -> IrExpression) -> IrExpression
