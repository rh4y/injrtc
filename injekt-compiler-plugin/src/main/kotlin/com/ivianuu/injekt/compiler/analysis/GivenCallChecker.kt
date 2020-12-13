package com.ivianuu.injekt.compiler.analysis

import com.ivianuu.injekt.compiler.DeclarationStore
import com.ivianuu.injekt.compiler.InjektErrors
import com.ivianuu.injekt.compiler.InjektWritableSlices
import com.ivianuu.injekt.compiler.SourcePosition
import com.ivianuu.injekt.compiler.descriptor
import com.ivianuu.injekt.compiler.resolution.BlockResolutionScope
import com.ivianuu.injekt.compiler.resolution.CallableGivenNode
import com.ivianuu.injekt.compiler.resolution.ClassResolutionScope
import com.ivianuu.injekt.compiler.resolution.ExternalResolutionScope
import com.ivianuu.injekt.compiler.resolution.FunctionResolutionScope
import com.ivianuu.injekt.compiler.resolution.GivenGraph
import com.ivianuu.injekt.compiler.resolution.GivenRequest
import com.ivianuu.injekt.compiler.resolution.InternalResolutionScope
import com.ivianuu.injekt.compiler.resolution.ResolutionScope
import com.ivianuu.injekt.compiler.resolution.resolveGiven
import com.ivianuu.injekt.compiler.resolution.toTypeRef
import com.ivianuu.injekt.compiler.uniqueKey
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.incremental.KotlinLookupLocation
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.DefaultValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class GivenCallChecker(
    private val bindingTrace: BindingTrace,
    private val declarationStore: DeclarationStore,
) : KtTreeVisitorVoid() {

    private fun ResolutionScope.check(call: ResolvedCall<*>, reportOn: KtElement) {
        val resultingDescriptor = call.resultingDescriptor
        if (resultingDescriptor !is FunctionDescriptor) return

        val givenInfo = declarationStore.givenInfoFor(resultingDescriptor)

        val requests = call
            .valueArguments
            .filterKeys { it.name in givenInfo.allGivens }
            .filter { it.value is DefaultValueArgument }
            .map {
                GivenRequest(
                    type = it.key.type.toTypeRef(),
                    required = it.key.name in givenInfo.requiredGivens,
                    callableFqName = resultingDescriptor.fqNameSafe,
                    parameterName = it.key.name,
                    callableKey = resultingDescriptor.uniqueKey()
                )
            }

        if (requests.isEmpty()) return

        val graph = resolveGiven(requests)

        when (graph) {
            is GivenGraph.Success -> {
                graph.givens.values
                    .filterIsInstance<CallableGivenNode>()
                    .forEach { given ->
                        val lookedUpDeclaration = when (val callable = given.callable) {
                            is ClassConstructorDescriptor -> callable.constructedClass
                            else -> callable
                        } as DeclarationDescriptor
                        when (val parent = lookedUpDeclaration.containingDeclaration) {
                            is ClassDescriptor -> parent.unsubstitutedMemberScope
                            is PackageFragmentDescriptor -> parent.getMemberScope()
                            else -> null
                        }?.recordLookup(given.callable.name, KotlinLookupLocation(reportOn))
                    }
                bindingTrace.record(
                    InjektWritableSlices.GIVEN_GRAPH,
                    SourcePosition(
                        reportOn.containingKtFile.virtualFilePath,
                        reportOn.startOffset,
                        reportOn.endOffset
                    ),
                    graph
                )
            }
            is GivenGraph.Error -> bindingTrace.report(
                InjektErrors.UNRESOLVED_GIVEN
                    .on(reportOn, graph)
            )
        }
    }

    private var scope = InternalResolutionScope(
        ExternalResolutionScope(declarationStore),
        declarationStore
    )

    private inline fun <R> inScope(scope: ResolutionScope, block: () -> R): R {
        val prevScope = this.scope
        this.scope = scope
        val result = block()
        this.scope = prevScope
        return result
    }

    override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
        inScope(ClassResolutionScope(
            bindingTrace.bindingContext,
            declarationStore,
            declaration.descriptor(bindingTrace.bindingContext) ?: return,
            scope
        )) {
            super.visitObjectDeclaration(declaration)
        }
    }

    override fun visitClass(klass: KtClass) {
        val parentScope = klass.companionObjects.singleOrNull()
            ?.let {
                ClassResolutionScope(
                    bindingTrace.bindingContext,
                    declarationStore,
                    it.descriptor(bindingTrace.bindingContext) ?: return,
                    scope
                )
            }
            ?: scope
        inScope(ClassResolutionScope(
            bindingTrace.bindingContext,
            declarationStore,
            klass.descriptor(bindingTrace.bindingContext) ?: return,
            parentScope
        )) {
            super.visitClass(klass)
        }
    }

    override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor) {
        visitFunction(constructor) { super.visitPrimaryConstructor(constructor) }
    }

    override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
        visitFunction(constructor) { super.visitSecondaryConstructor(constructor) }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        visitFunction(function) { super.visitNamedFunction(function) }
    }

    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
        visitFunction(lambdaExpression.functionLiteral) {
            super.visitLambdaExpression(lambdaExpression)
        }
    }

    private fun visitFunction(function: KtFunction, block: () -> Unit) {
        inScope(FunctionResolutionScope(
            declarationStore,
            scope,
            function.descriptor(bindingTrace.bindingContext) ?: return
        )) { block() }
    }

    private var blockScope: ResolutionScope? = null
    override fun visitBlockExpression(expression: KtBlockExpression) {
        val prevScope = blockScope
        val scope = BlockResolutionScope(declarationStore, scope)
        this.blockScope = scope
        inScope(scope) {
            super.visitBlockExpression(expression)
        }
        blockScope = prevScope
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val descriptor = property.descriptor<VariableDescriptor>(bindingTrace.bindingContext)
            ?: return
        blockScope?.addIfNeeded(descriptor)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        scope.check(expression.getResolvedCall(bindingTrace.bindingContext) ?: return, expression)
    }

}
