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

package com.ivianuu.injekt.compiler.transform.android

import com.ivianuu.injekt.compiler.AndroidSymbols
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.transform.AbstractInjektTransformer
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.overrides
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// todo check android classes
// todo check onCreate not final
// todo check onCreate calls super
// todo check is not a abstract class
// todo check is final?
class AndroidEntryPointTransformer(pluginContext: IrPluginContext) :
    AbstractInjektTransformer(pluginContext) {

    private val androidSymbols = AndroidSymbols(pluginContext)

    override fun visitClass(declaration: IrClass): IrStatement {
        if (!declaration.hasAnnotation(InjektFqNames.AndroidEntryPoint))
            return super.visitClass(declaration)

        when {
            declaration.isSubclassOf(androidSymbols.application.owner) ->
                transformApplication(declaration)
            declaration.isSubclassOf(androidSymbols.componentActivity.owner) ->
                transformActivity(declaration)
            declaration.isSubclassOf(androidSymbols.fragment.owner) ->
                transformFragment(declaration)
            declaration.isSubclassOf(androidSymbols.service.owner) ->
                transformService(declaration)
            else -> error("Unsupported android entry point ${declaration.render()}") // todo remove
        }

        return super.visitClass(declaration)
    }

    private fun transformApplication(application: IrClass) {
        transformAndroidClass(
            clazz = application,
            functionPredicate = {
                it.name.asString() == "onCreate" &&
                        it.valueParameters.isEmpty()
            },
            createFunction = { superClass, superOnCreate ->
                application.addFunction {
                    name = Name.identifier("onCreate")
                    returnType = irBuiltIns.unitType
                }.apply {
                    overrides(superOnCreate)
                    dispatchReceiverParameter = application.thisReceiver!!.copyTo(this)
                    body = DeclarationIrBuilder(pluginContext, symbol).run {
                        irBlockBody {
                            +irCall(superOnCreate, null, superClass.symbol).apply {
                                dispatchReceiver = irGet(dispatchReceiverParameter!!)
                            }
                        }
                    }
                }
            },
            componentAccessor = { _, thisExpr ->
                irCall(
                    pluginContext.referenceProperties(
                        FqName("com.ivianuu.injekt.android.applicationComponent")
                    ).single().owner.getter!!
                ).apply {
                    extensionReceiver = thisExpr()
                }
            },
            generateComponents = true
        )
    }

    private fun transformActivity(activity: IrClass) {
        transformAndroidClass(
            clazz = activity,
            functionPredicate = {
                it.name.asString() == "onCreate" &&
                        it.valueParameters.size == 1 &&
                        it.valueParameters.single().type == androidSymbols.bundle.defaultType.makeNullable()
            },
            createFunction = { superClass, superOnCreate ->
                activity.addFunction {
                    name = Name.identifier("onCreate")
                    returnType = irBuiltIns.unitType
                }.apply {
                    overrides(superOnCreate)
                    dispatchReceiverParameter = activity.thisReceiver!!.copyTo(this)
                    val savedInstanceStateValueParameter = addValueParameter(
                        "savedInstanceState",
                        androidSymbols.bundle.defaultType.makeNullable()
                    )
                    body = DeclarationIrBuilder(pluginContext, symbol).run {
                        irBlockBody {
                            +irCall(superOnCreate, null, superClass.symbol).apply {
                                dispatchReceiver = irGet(dispatchReceiverParameter!!)
                                putValueArgument(
                                    0, irGet(savedInstanceStateValueParameter)
                                )
                            }
                        }
                    }
                }
            },
            componentAccessor = { _, thisExpr ->
                irCall(
                    pluginContext.referenceProperties(
                        FqName("com.ivianuu.injekt.android.activityComponent")
                    ).single().owner.getter!!
                ).apply {
                    extensionReceiver = thisExpr()
                }
            },
            generateComponents = false
        )
    }

    private fun transformFragment(fragment: IrClass) {
        transformAndroidClass(
            clazz = fragment,
            functionPredicate = {
                it.name.asString() == "onCreate" &&
                        it.valueParameters.size == 1 &&
                        it.valueParameters.single().type == androidSymbols.bundle.defaultType.makeNullable()
            },
            createFunction = { superClass, superOnCreate ->
                fragment.addFunction {
                    name = Name.identifier("onCreate")
                    returnType = irBuiltIns.unitType
                }.apply {
                    overrides(superOnCreate)
                    dispatchReceiverParameter = fragment.thisReceiver!!.copyTo(this)
                    val savedInstanceStateValueParameter = addValueParameter(
                        "savedInstanceState",
                        androidSymbols.bundle.defaultType.makeNullable()
                    )
                    body = DeclarationIrBuilder(pluginContext, symbol).run {
                        irBlockBody {
                            +irCall(superOnCreate, null, superClass.symbol).apply {
                                dispatchReceiver = irGet(dispatchReceiverParameter!!)
                                putValueArgument(
                                    0, irGet(savedInstanceStateValueParameter)
                                )
                            }
                        }
                    }
                }
            },
            componentAccessor = { _, thisExpr ->
                irCall(
                    pluginContext.referenceProperties(
                        FqName("com.ivianuu.injekt.android.fragmentComponent")
                    ).single().owner.getter!!
                ).apply {
                    extensionReceiver = thisExpr()
                }
            },
            generateComponents = false
        )
    }

    private fun transformAndroidClass(
        clazz: IrClass,
        functionPredicate: (IrSimpleFunction) -> Boolean,
        createFunction: (IrClass, IrSimpleFunction) -> IrSimpleFunction,
        componentAccessor: IrBuilderWithScope.(IrSimpleFunction, () -> IrExpression) -> IrExpression,
        generateComponents: Boolean
    ) {
        val superClass: IrClass = clazz.superTypes
            .single { it.classOrNull != null }
            .classOrNull!!
            .owner

        val superFunction = superClass
            .functions
            .single(functionPredicate)

        var thisOnCreate: IrSimpleFunction? = clazz
            .functions
            .singleOrNull(functionPredicate)

        if (thisOnCreate?.origin == IrDeclarationOrigin.FAKE_OVERRIDE) {
            clazz.declarations -= thisOnCreate
            thisOnCreate = null
        }

        if (thisOnCreate == null) {
            thisOnCreate = createFunction(superClass, superFunction)
        }

        thisOnCreate.transformChildrenVoid(object : IrElementTransformerVoid() {
            private var initialized = false
            override fun visitCall(expression: IrCall): IrExpression {
                if (initialized || expression.superQualifierSymbol != superClass.symbol ||
                    expression.symbol != superFunction.symbol
                ) return super.visitCall(expression)
                initialized = true
                return DeclarationIrBuilder(pluginContext, thisOnCreate.symbol).run {
                    irBlock {
                        if (generateComponents) {
                            +IrCallImpl(
                                expression.endOffset + 1,
                                expression.endOffset + 2,
                                irBuiltIns.unitType,
                                pluginContext.referenceFunctions(
                                    FqName("com.ivianuu.injekt.composition.generateCompositions")
                                ).single()
                            )
                        }
                        +IrCallImpl(
                            expression.endOffset + 3,
                            expression.endOffset + 4,
                            irBuiltIns.unitType,
                            pluginContext.referenceFunctions(
                                FqName("com.ivianuu.injekt.composition.inject")
                            ).single()
                        ).apply {
                            putTypeArgument(
                                0,
                                clazz.defaultType
                            )

                            extensionReceiver = componentAccessor(this@run, thisOnCreate) {
                                expression.dispatchReceiver!!.deepCopyWithSymbols()
                            }

                            putValueArgument(
                                0,
                                expression.dispatchReceiver!!.deepCopyWithSymbols()
                            )
                        }
                        +expression
                    }
                }
            }
        })
    }

    private fun transformService(service: IrClass) {
        val createServiceComponent = pluginContext.referenceFunctions(
            FqName("com.ivianuu.injekt.android.newServiceComponent")
        ).single { it.owner.extensionReceiverParameter != null }

        val componentField = service.addField(
            "\$component",
            createServiceComponent.owner.returnType
        )

        transformAndroidClass(
            clazz = service,
            functionPredicate = {
                it.name.asString() == "onCreate" &&
                        it.valueParameters.isEmpty()
            },
            createFunction = { superClass, superOnCreate ->
                service.addFunction {
                    name = Name.identifier("onCreate")
                    returnType = irBuiltIns.unitType
                }.apply {
                    overrides(superOnCreate)
                    dispatchReceiverParameter = service.thisReceiver!!.copyTo(this)
                    body = DeclarationIrBuilder(pluginContext, symbol).run {
                        irBlockBody {
                            +irCall(superOnCreate, null, superClass.symbol).apply {
                                dispatchReceiver = irGet(dispatchReceiverParameter!!)
                            }
                        }
                    }
                }
            },
            componentAccessor = { _, thisExpr ->
                irBlock {
                    val tmpComponent = irTemporary(
                        irCall(createServiceComponent).apply {
                            extensionReceiver = thisExpr()
                        }
                    )

                    +irSetField(
                        thisExpr(),
                        componentField,
                        irGet(tmpComponent)
                    )

                    +irGet(tmpComponent)
                }
            },
            generateComponents = false
        )
    }

}
