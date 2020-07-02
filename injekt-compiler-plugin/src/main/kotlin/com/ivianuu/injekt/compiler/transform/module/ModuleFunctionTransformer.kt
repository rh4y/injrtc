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

package com.ivianuu.injekt.compiler.transform.module

import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.InjektNameConventions
import com.ivianuu.injekt.compiler.NameProvider
import com.ivianuu.injekt.compiler.PropertyPath
import com.ivianuu.injekt.compiler.addMetadataIfNotLocal
import com.ivianuu.injekt.compiler.addToFileOrAbove
import com.ivianuu.injekt.compiler.buildClass
import com.ivianuu.injekt.compiler.isExternalDeclaration
import com.ivianuu.injekt.compiler.setClassKind
import com.ivianuu.injekt.compiler.transform.AbstractFunctionTransformer
import com.ivianuu.injekt.compiler.transform.InjektDeclarationIrBuilder
import com.ivianuu.injekt.compiler.transform.InjektDeclarationStore
import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.ir.copyBodyTo
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.copyTypeAndValueArgumentsFrom
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class ModuleFunctionTransformer(
    pluginContext: IrPluginContext,
    private val declarationStore: InjektDeclarationStore
) : AbstractFunctionTransformer(pluginContext, TransformOrder.BottomUp) {

    fun getModuleFunctionForClass(moduleClass: IrClass): IrFunction =
        transformedFunctions.values
            .single { it.returnType.classOrNull!!.owner == moduleClass }

    fun getModuleClassForFunction(moduleFunction: IrFunction): IrClass =
        transformFunctionIfNeeded(moduleFunction).returnType.classOrNull!!.owner

    override fun visitModuleFragment(declaration: IrModuleFragment): IrModuleFragment {
        super.visitModuleFragment(declaration)
        transformedFunctions
            .filterNot { it.key.isExternalDeclaration() }
            .forEach {
                it.value.returnType.classOrNull!!.owner.addToFileOrAbove(it.value)
            }
        return declaration
    }

    override fun needsTransform(function: IrFunction): Boolean =
        function.hasAnnotation(InjektFqNames.Module)

    override fun transform(function: IrFunction, callback: (IrFunction) -> Unit) {
        val transformedFunction = IrFunctionImpl(
            function.startOffset,
            function.endOffset,
            function.origin,
            IrSimpleFunctionSymbolImpl(
                WrappedSimpleFunctionDescriptor(
                    annotations = function.descriptor.annotations,
                    sourceElement = function.descriptor.source
                )
            ),
            InjektNameConventions.getTransformedModuleFunctionNameForModule(
                function.getPackageFragment()!!.fqName,
                function.descriptor.fqNameSafe
            ),
            Visibilities.PUBLIC,
            function.descriptor.modality,
            irBuiltIns.unitType,
            function.isInline,
            function.isExternal,
            function.descriptor.isTailrec,
            function.isSuspend,
            function.descriptor.isOperator,
            function.isExpect,
            false
        ).apply {
            (symbol.descriptor as WrappedSimpleFunctionDescriptor).bind(this)
            parent = function.file
            addMetadataIfNotLocal()

            annotations = function.annotations.map { it.deepCopyWithSymbols() }

            copyTypeParametersFrom(function)

            valueParameters = function.allParameters.mapIndexed { index, valueParameter ->
                valueParameter.copyTo(
                    this,
                    index = index
                )
            }

            body = function.copyBodyTo(this)
        }
        callback(transformedFunction)

        val moduleDescriptor = ModuleDescriptor(
            transformedFunction,
            function,
            pluginContext,
            symbols
        )

        val moduleClass = buildClass {
            name = InjektNameConventions.getModuleClassNameForModuleFunction(
                function.getPackageFragment()!!.fqName,
                transformedFunction.descriptor.fqNameSafe
            )
            visibility = transformedFunction.visibility
        }.apply clazz@{
            moduleDescriptor.moduleClass = this
            parent = transformedFunction.file
            createImplicitParameterDeclarationWithWrappedDescriptor()
            addMetadataIfNotLocal()
            copyTypeParametersFrom(transformedFunction)
            addChild(moduleDescriptor.clazz)
        }

        transformedFunction.addValueParameter(
            "moduleMarker",
            irBuiltIns.anyNType
        )

        transformedFunction.returnType = moduleClass.typeWith(
            transformedFunction.typeParameters.map { it.defaultType }
        )

        val nameProvider = NameProvider()

        val allDeclarations = mutableListOf<ModuleDeclaration>()
        val moduleDeclarationFactory = ModuleDeclarationFactory(
            transformedFunction,
            function,
            moduleClass,
            pluginContext,
            declarationStore,
            nameProvider,
            symbols
        )
        val variableByDeclaration = mutableMapOf<ModuleDeclaration, IrVariable>()

        transformedFunction.rewriteTransformedFunctionRefs()

        val bodyStatements = transformedFunction.body?.statements?.toList() ?: emptyList()

        transformedFunction.body =
            DeclarationIrBuilder(pluginContext, transformedFunction.symbol).irBlockBody {
                bodyStatements.forEach { stmt ->
                    if (stmt !is IrCall) {
                        +stmt
                        return@forEach
                    }

                    val callee = stmt.symbol.owner
                    val declarations = moduleDeclarationFactory.createDeclarations(
                        callee, stmt
                    )
                    allDeclarations += declarations
                    moduleDescriptor.addDeclarations(declarations)

                    if (declarations.isEmpty()) {
                        +stmt
                    } else {
                        declarations.forEach { declaration ->
                            if (declaration.path is PropertyPath) {
                                variableByDeclaration[declaration] =
                                    irTemporary(declaration.initializer!!)
                            }
                        }
                    }
                }

                var isStatic = false

                val moduleConstructor = moduleClass.addConstructor {
                    returnType = moduleClass.defaultType
                    isPrimary = true
                    visibility = Visibilities.PUBLIC
                }.apply {
                    val declarationsWithProperties = allDeclarations
                        .filter { it.path is PropertyPath }
                        .map { it to it.path as PropertyPath }

                    isStatic = declarationsWithProperties
                        .none {
                            var captures = false
                            it.first.initializer!!.transform(
                                object : IrElementTransformerVoid() {
                                    override fun visitGetValue(expression: IrGetValue): IrExpression {
                                        if (expression.symbol.owner.parent == transformedFunction) {
                                            captures = true
                                        }
                                        return super.visitGetValue(expression)
                                    }
                                }, null
                            )
                            captures
                        } && moduleClass.typeParameters.isEmpty()

                    val valueParametersByProperties = if (isStatic) {
                        emptyMap()
                    } else {
                        declarationsWithProperties
                            .map { it.second.property }
                            .associateWith {
                                addValueParameter(
                                    it.field.name.asString(),
                                    it.getter.returnType
                                )
                            }
                    }

                    body = InjektDeclarationIrBuilder(pluginContext, symbol).run {
                        builder.irBlockBody {
                            initializeClassWithAnySuperClass(moduleClass.symbol)
                            if (!isStatic) {
                                valueParametersByProperties.forEach { (property, valueParameter) ->
                                    +irSetField(
                                        irGet(moduleClass.thisReceiver!!),
                                        property.field,
                                        irGet(valueParameter)
                                    )
                                }
                            } else {
                                declarationsWithProperties
                                    .forEach { (declaration, path) ->
                                        +irSetField(
                                            irGet(moduleClass.thisReceiver!!),
                                            path.property.field,
                                            declaration.initializer!!
                                                .deepCopyWithVariables()
                                        )
                                    }
                            }
                        }
                    }
                }

                if (isStatic) {
                    moduleClass.setClassKind(ClassKind.OBJECT)
                    doBuild().statements.clear()
                }

                +irReturn(
                    if (isStatic) {
                        irGetObject(moduleClass.symbol)
                    } else {
                        irCall(moduleConstructor).apply {
                            variableByDeclaration.values.forEachIndexed { index, variable ->
                                putValueArgument(index, irGet(variable))
                            }
                        }
                    }
                )
            }
    }

    override fun transformExternal(function: IrFunction, callback: (IrFunction) -> Unit) {
        callback(
            pluginContext.referenceFunctions(
                function.getPackageFragment()!!.fqName
                    .child(
                        InjektNameConventions.getTransformedModuleFunctionNameForModule(
                            function.getPackageFragment()!!.fqName,
                            function.descriptor.fqNameSafe
                        )
                    )
            ).single {
                it.owner.valueParameters.lastOrNull()?.name?.asString() == "moduleMarker"
            }.owner
        )
    }

    override fun transformCall(
        transformedCallee: IrFunction,
        expression: IrCall
    ): IrCall {
        return IrCallImpl(
            expression.startOffset,
            expression.endOffset,
            transformedCallee.returnType,
            transformedCallee.symbol,
            expression.origin,
            expression.superQualifierSymbol
        ).apply {
            copyTypeAndValueArgumentsFrom(expression, receiversAsArguments = true)
            putValueArgument(
                valueArgumentsCount - 1,
                DeclarationIrBuilder(pluginContext, symbol).irNull()
            )
        }
    }

}
