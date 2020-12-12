package com.ivianuu.injekt.compiler.transform

import com.ivianuu.injekt.compiler.DeclarationStore
import com.ivianuu.injekt.compiler.resolution.BlockResolutionScope
import com.ivianuu.injekt.compiler.resolution.CallableGivenNode
import com.ivianuu.injekt.compiler.resolution.ClassResolutionScope
import com.ivianuu.injekt.compiler.resolution.ClassifierRef
import com.ivianuu.injekt.compiler.resolution.CollectionGivenNode
import com.ivianuu.injekt.compiler.resolution.ExternalResolutionScope
import com.ivianuu.injekt.compiler.resolution.FunctionResolutionScope
import com.ivianuu.injekt.compiler.resolution.GivenRequest
import com.ivianuu.injekt.compiler.resolution.InternalResolutionScope
import com.ivianuu.injekt.compiler.resolution.ProviderGivenNode
import com.ivianuu.injekt.compiler.resolution.ResolutionScope
import com.ivianuu.injekt.compiler.resolution.TypeRef
import com.ivianuu.injekt.compiler.resolution.fullyExpandedType
import com.ivianuu.injekt.compiler.resolution.getSubstitutionMap
import com.ivianuu.injekt.compiler.resolution.isSubTypeOf
import com.ivianuu.injekt.compiler.resolution.resolveGivenCandidates
import com.ivianuu.injekt.compiler.resolution.substitute
import com.ivianuu.injekt.compiler.resolution.subtypeView
import com.ivianuu.injekt.compiler.resolution.toClassifierRef
import com.ivianuu.injekt.compiler.resolution.toGivenNode
import com.ivianuu.injekt.compiler.resolution.toTypeRef
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContextImpl
import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ParameterDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class GivenCallTransformer(
    private val declarationStore: DeclarationStore,
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoid() {

    private fun ResolutionScope.fillGivens(
        call: IrFunctionAccessExpression,
        substitutionMap: Map<ClassifierRef, TypeRef>,
    ) {
        val callee = call.symbol.owner
        val calleeDescriptor = callee.descriptor
        val givenInfo = declarationStore.givenInfoFor(calleeDescriptor)

        if (givenInfo.allGivens.isNotEmpty()) {
            callee
                .valueParameters
                .filter { it.name in givenInfo.allGivens }
                .filter { call.getValueArgument(it.index) == null }
                .map {
                    it to expressionFor(
                        GivenRequest(
                            it.descriptor.type.toTypeRef().substitute(substitutionMap),
                            it.name in givenInfo.requiredGivens,
                            it.descriptor.fqNameSafe
                        ),
                        call.symbol
                    )
                }
                .forEach { call.putValueArgument(it.first.index, it.second) }
        }
    }

    private fun ResolutionScope.expressionFor(
        request: GivenRequest,
        symbol: IrSymbol,
    ): IrExpression {
        return expressionsByType.getOrPut(request.type) {
            val given = resolveGivenCandidates(
                declarationStore,
                request
            ).let { givens ->
                givens.singleOrNull() ?: error("Wtf $request $givens")
            }
            when (given) {
                is CallableGivenNode -> callableExpression(given, symbol)
                is ProviderGivenNode -> providerExpression(given, symbol)
                is CollectionGivenNode -> collectionExpression(given, symbol)
            }
        }()
    }

    private fun ResolutionScope.providerExpression(
        given: ProviderGivenNode,
        symbol: IrSymbol,
    ): () -> IrExpression = {
        DeclarationIrBuilder(pluginContext, symbol)
            .irLambda(given.type.toIrType(pluginContext)) {
                expressionFor(given.dependencies.single(), symbol)
            }
    }

    private fun ResolutionScope.collectionExpression(
        given: CollectionGivenNode,
        symbol: IrSymbol,
    ): () -> IrExpression {
        return if (given.elements.size == 1) {
            callableExpression(
                given.elements.single()
                    .toGivenNode(given.type, declarationStore),
                symbol
            )
        } else {
            {
                DeclarationIrBuilder(pluginContext, symbol).irBlock {
                    if (given.type.isSubTypeOf(pluginContext.builtIns.map.defaultType.toTypeRef())) {
                        val keyType =
                            given.type.fullyExpandedType.typeArguments[0]
                                .toIrType(pluginContext)
                        val valueType =
                            given.type.fullyExpandedType.typeArguments[1]
                                .toIrType(pluginContext)

                        val mutableMapOf = pluginContext.referenceFunctions(
                            FqName("kotlin.collections.mutableMapOf")
                        ).single { it.owner.valueParameters.isEmpty() }

                        val mapPutAll = mutableMapOf.owner.returnType
                            .classOrNull!!
                            .owner
                            .functions
                            .single { it.name.asString() == "putAll" }

                        val tmpMap = irTemporary(
                            irCall(mutableMapOf)
                                .apply {
                                    putTypeArgument(0, keyType)
                                    putTypeArgument(1, valueType)
                                }
                        )

                        given.elements
                            .forEach {
                                +irCall(mapPutAll).apply {
                                    dispatchReceiver = irGet(tmpMap)
                                    putValueArgument(
                                        0,
                                        callableExpression(
                                            it.toGivenNode(given.type, declarationStore),
                                            symbol
                                        )()
                                    )
                                }
                            }

                        +irGet(tmpMap)
                    } else if (given.type.isSubTypeOf(pluginContext.builtIns.set.defaultType.toTypeRef())) {
                        val elementType =
                            given.type.fullyExpandedType.typeArguments.single()
                                .toIrType(pluginContext)

                        val mutableSetOf = pluginContext.referenceFunctions(
                            FqName("kotlin.collections.mutableSetOf")
                        ).single { it.owner.valueParameters.isEmpty() }

                        val setAddAll = mutableSetOf.owner.returnType
                            .classOrNull!!
                            .owner
                            .functions
                            .single { it.name.asString() == "addAll" }

                        val tmpSet = irTemporary(
                            irCall(mutableSetOf)
                                .apply { putTypeArgument(0, elementType) }
                        )

                        given.elements
                            .forEach {
                                +irCall(setAddAll).apply {
                                    dispatchReceiver = irGet(tmpSet)
                                    putValueArgument(
                                        0,
                                        callableExpression(
                                            it.toGivenNode(given.type, declarationStore),
                                            symbol
                                        )()
                                    )
                                }
                            }

                        +irGet(tmpSet)
                    }
                }
            }
        }
    }

    private fun ResolutionScope.callableExpression(
        given: CallableGivenNode,
        symbol: IrSymbol,
    ): () -> IrExpression = {
        when (given.callable) {
            is ConstructorDescriptor -> classExpression(given.type, given.callable, symbol)
            is PropertyDescriptor -> propertyExpression(given.type, given.callable, symbol)
            is FunctionDescriptor -> functionExpression(given.type, given.callable, symbol)
            is ReceiverParameterDescriptor -> parameterExpression(given.callable, symbol)
            is ValueParameterDescriptor -> parameterExpression(given.callable, symbol)
            is VariableDescriptor -> variableExpression(given.callable, symbol)
            else -> error("Unsupported callable $given")
        }
    }

    private fun ResolutionScope.classExpression(
        type: TypeRef,
        descriptor: ConstructorDescriptor,
        symbol: IrSymbol,
    ): IrExpression {
        return if (descriptor.constructedClass.kind == ClassKind.OBJECT) {
            val clazz =
                pluginContext.referenceClass(descriptor.constructedClass.fqNameSafe)!!
            DeclarationIrBuilder(pluginContext, symbol)
                .irGetObject(clazz)
        } else {
            val constructor =
                pluginContext.referenceConstructors(descriptor.constructedClass.fqNameSafe)
                    .single()
                    .owner
            DeclarationIrBuilder(pluginContext, symbol)
                .irCall(constructor.symbol)
                .apply {
                    val substitutionMap = getSubstitutionMap(
                        listOf(type to constructor.constructedClass.descriptor.defaultType.toTypeRef()
                            .subtypeView(type.classifier)!!)
                    )

                    constructor.constructedClass.typeParameters
                        .map {
                            substitutionMap[it.descriptor.toClassifierRef()]
                                ?: error("No substitution found for ${it.dump()}")
                        }
                        .forEachIndexed { index, typeArgument ->
                            putTypeArgument(index, typeArgument.toIrType(pluginContext))
                        }

                    fillGivens(this, substitutionMap)
                }
        }
    }

    private fun ResolutionScope.propertyExpression(
        type: TypeRef,
        descriptor: PropertyDescriptor,
        symbol: IrSymbol,
    ): IrExpression {
        val property = pluginContext.referenceProperties(descriptor.fqNameSafe)
            .single()
        val getter = property.owner.getter!!
        return DeclarationIrBuilder(pluginContext, symbol)
            .irCall(getter.symbol)
            .apply {
                val dispatchReceiverParameter = getter.dispatchReceiverParameter
                if (dispatchReceiverParameter != null) {
                    dispatchReceiver =
                        if (dispatchReceiverParameter.type.classOrNull?.owner?.kind == ClassKind.OBJECT) {
                            DeclarationIrBuilder(pluginContext, symbol)
                                .irGetObject(dispatchReceiverParameter.type.classOrNull!!)
                        } else {
                            dispatchReceiverAccessors
                                .last { it.first == dispatchReceiverParameter.type.classOrNull?.owner }
                                .second()
                        }
                }
                val substitutionMap = getSubstitutionMap(
                    listOf(type to getter.descriptor.returnType!!.toTypeRef()),
                    getter.typeParameters.map { it.descriptor.toClassifierRef() }
                )

                getter.typeParameters
                    .map {
                        substitutionMap[it.descriptor.toClassifierRef()]
                            ?: error("No substitution found for ${it.dump()}")
                    }
                    .forEachIndexed { index, typeArgument ->
                        putTypeArgument(index, typeArgument.toIrType(pluginContext))
                    }

                fillGivens(this, substitutionMap)
            }
    }

    private fun ResolutionScope.functionExpression(
        type: TypeRef,
        descriptor: FunctionDescriptor,
        symbol: IrSymbol,
    ): IrExpression {
        val function = descriptor.irFunction()
        return DeclarationIrBuilder(pluginContext, symbol)
            .irCall(function.symbol)
            .apply {
                val dispatchReceiverParameter = function.dispatchReceiverParameter
                if (dispatchReceiverParameter != null) {
                    dispatchReceiver =
                        if (dispatchReceiverParameter.type.classOrNull?.owner?.kind == ClassKind.OBJECT) {
                            DeclarationIrBuilder(pluginContext, symbol)
                                .irGetObject(dispatchReceiverParameter.type.classOrNull!!)
                        } else {
                            dispatchReceiverAccessors.reversed()
                                .first { it.first == dispatchReceiverParameter.type.classOrNull?.owner }
                                .second()
                        }
                }

                val substitutionMap = getSubstitutionMap(
                    listOf(type to function.descriptor.returnType!!.toTypeRef()),
                    function.typeParameters.map { it.descriptor.toClassifierRef() }
                )

                function.typeParameters
                    .map {
                        substitutionMap[it.descriptor.toClassifierRef()]
                            ?: error("No substitution found for ${it.dump()}")
                    }
                    .forEachIndexed { index, typeArgument ->
                        putTypeArgument(index, typeArgument.toIrType(pluginContext))
                    }

                fillGivens(this, substitutionMap)
            }
    }

    private fun ResolutionScope.parameterExpression(
        descriptor: ParameterDescriptor,
        symbol: IrSymbol,
    ): IrExpression {
        val valueParameter =
            when (val containingDeclaration = descriptor.containingDeclaration) {
                is ClassConstructorDescriptor -> containingDeclaration.irConstructor()
                    .allParameters
                    .single { it.name == descriptor.name }
                is FunctionDescriptor -> containingDeclaration.irFunction()
                    .let { function ->
                        function.allParameters
                            .filter { it != function.dispatchReceiverParameter }
                    }
                    .single { it.name == descriptor.name }
                else -> error("Unexpected parent $descriptor $containingDeclaration")
            }

        return DeclarationIrBuilder(pluginContext, symbol)
            .irGet(valueParameter)
    }

    private fun ResolutionScope.variableExpression(
        descriptor: VariableDescriptor,
        symbol: IrSymbol,
    ): IrExpression {
        return DeclarationIrBuilder(pluginContext, symbol)
            .irGet(variables.single { it.descriptor == descriptor })
    }

    private fun ClassConstructorDescriptor.irConstructor() =
        pluginContext.symbolTable.referenceConstructor(original)
            .also {
                try {
                    with((pluginContext as IrPluginContextImpl).linker) {
                        getDeclaration(it)
                        postProcess()
                    }
                } catch (e: Throwable) {
                }
            }
            .owner

    private fun FunctionDescriptor.irFunction() =
        pluginContext.symbolTable.referenceSimpleFunction(original)
            .also {
                try {
                    with((pluginContext as IrPluginContextImpl).linker) {
                        getDeclaration(it)
                        postProcess()
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            .owner

    private var scope = InternalResolutionScope(
        ExternalResolutionScope(declarationStore),
        declarationStore
    )

    private val dispatchReceiverAccessors = mutableListOf<Pair<IrClass, () -> IrExpression>>()

    private val variables = mutableListOf<IrVariable>()

    private inline fun <R> inScope(scope: ResolutionScope, block: () -> R): R {
        val prevScope = this.scope
        this.scope = scope
        val result = block()
        this.scope = prevScope
        return result
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        dispatchReceiverAccessors.push(
            declaration to {
                DeclarationIrBuilder(pluginContext, declaration.symbol)
                    .irGet(declaration.thisReceiver!!)
            }
        )
        val result = if (declaration.kind == ClassKind.OBJECT) {
            inScope(ClassResolutionScope(
                pluginContext.bindingContext,
                declarationStore,
                declaration.descriptor,
                scope
            )) { super.visitClass(declaration) }
        } else {
            val parentScope = declaration.companionObject()
                ?.let { it as? IrClass }
                ?.let {
                    ClassResolutionScope(
                        pluginContext.bindingContext,
                        declarationStore,
                        it.descriptor,
                        scope
                    )
                } ?: scope
            inScope(ClassResolutionScope(
                pluginContext.bindingContext,
                declarationStore,
                declaration.descriptor,
                parentScope
            )) { super.visitClass(declaration) }
        }
        dispatchReceiverAccessors.pop()
        return result
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val dispatchReceiver = declaration.dispatchReceiverParameter?.type?.classOrNull?.owner
        if (dispatchReceiver != null) {
            dispatchReceiverAccessors.push(
                dispatchReceiver to {
                    DeclarationIrBuilder(pluginContext, declaration.symbol)
                        .irGet(declaration.dispatchReceiverParameter!!)
                }
            )
        }
        val result =
            inScope(FunctionResolutionScope(declarationStore, scope, declaration.descriptor)) {
                super.visitFunction(declaration)
            }
        if (dispatchReceiver != null) {
            dispatchReceiverAccessors.pop()
        }
        return result
    }

    private var blockScope: ResolutionScope? = null

    override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer): IrStatement {
        return inBlockScope(BlockResolutionScope(declarationStore, scope)) {
            super.visitAnonymousInitializer(declaration)
        }
    }

    override fun visitBlock(expression: IrBlock): IrExpression {
        return inBlockScope(BlockResolutionScope(declarationStore, scope)) {
            super.visitBlock(expression)
        }
    }

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        return inBlockScope(BlockResolutionScope(declarationStore, scope)) {
            super.visitBlockBody(body)
        }
    }

    private inline fun <R> inBlockScope(scope: ResolutionScope, block: () -> R): R {
        val prevScope = this.blockScope
        this.blockScope = scope
        val result = inScope(scope, block)
        this.blockScope = prevScope
        return result
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        return super.visitVariable(declaration)
            .also {
                blockScope?.addIfNeeded(declaration.descriptor)
                variables += declaration
            }
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression) =
        super.visitFunctionAccess(expression.apply {
            val givenInfo = declarationStore.givenInfoFor(expression.symbol.descriptor)
            if (givenInfo.allGivens.isNotEmpty()) {
                try {
                    val substitutionMap = getSubstitutionMap(
                        (0 until expression.typeArgumentsCount)
                            .map { getTypeArgument(it)!!.toKotlinType().toTypeRef() }
                            .zip(
                                expression.symbol.descriptor.typeParameters
                                    .map { it.defaultType.toTypeRef() }
                            )
                    )
                    scope.fillGivens(this, substitutionMap)
                } catch (e: Throwable) {
                    throw RuntimeException("Wtf ${expression.dump()}", e)
                }
            }
        })


}
