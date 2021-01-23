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

package com.ivianuu.injekt.compiler.analysis

import com.ivianuu.injekt.compiler.DeclarationStore
import com.ivianuu.injekt.compiler.InjektErrors
import com.ivianuu.injekt.compiler.InjektFqNames
import com.ivianuu.injekt.compiler.hasAnnotation
import com.ivianuu.injekt.compiler.resolution.contributionKind
import org.jetbrains.kotlin.backend.common.descriptors.allParameters
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.builtins.isSuspendFunctionType
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.checkers.DeclarationChecker
import org.jetbrains.kotlin.resolve.checkers.DeclarationCheckerContext

class GivenChecker(private val declarationStore: DeclarationStore) : DeclarationChecker {

    override fun check(
        declaration: KtDeclaration,
        descriptor: DeclarationDescriptor,
        context: DeclarationCheckerContext,
    ) {
        checkType(descriptor, declaration, context.trace)

        if (descriptor is SimpleFunctionDescriptor) {
            descriptor.allParameters
                .filterNot { it === descriptor.dispatchReceiverParameter }
                .checkParameters(declaration, descriptor, context.trace)
        } else if (descriptor is ConstructorDescriptor) {
            descriptor.valueParameters
                .checkParameters(declaration, descriptor, context.trace)
        } else if (descriptor is ClassDescriptor) {
            val givenConstructors = descriptor.constructors
                .filter { it.hasAnnotation(InjektFqNames.Given) }

            if (descriptor.hasAnnotation(InjektFqNames.Given) &&
                givenConstructors.isNotEmpty()
            ) {
                context.trace.report(
                    InjektErrors.GIVEN_CLASS_WITH_GIVEN_CONSTRUCTOR
                        .on(declaration)
                )
            }

            if (givenConstructors.size > 1) {
                context.trace.report(
                    InjektErrors.CLASS_WITH_MULTIPLE_GIVEN_CONSTRUCTORS
                        .on(declaration)
                )
            }

            descriptor.constructors
                .forEach {
                    it.valueParameters
                        .checkParameters(it.findPsi() as KtDeclaration, descriptor, context.trace)
                }
        } else if (descriptor is PropertyDescriptor) {
            if (descriptor.hasAnnotation(InjektFqNames.Given) &&
                descriptor.extensionReceiverParameter != null &&
                descriptor.extensionReceiverParameter?.type?.contributionKind(
                    declarationStore
                ) == null
            ) {
                context.trace.report(
                    InjektErrors.NON_GIVEN_PARAMETER_ON_GIVEN_DECLARATION
                        .on(
                            descriptor.extensionReceiverParameter?.findPsi() ?: declaration,
                            when {
                                descriptor.hasAnnotation(InjektFqNames.Given) -> InjektFqNames.Given.shortName()
                                descriptor.hasAnnotation(InjektFqNames.Module) -> InjektFqNames.Module.shortName()
                                descriptor.hasAnnotation(InjektFqNames.GivenSetElement) -> InjektFqNames.GivenSetElement.shortName()
                                else -> error("")
                            }
                        )
                )
            }
        }
    }

    private fun checkType(
        descriptor: DeclarationDescriptor,
        declaration: KtDeclaration,
        trace: BindingTrace
    ) {
        val type = when (descriptor) {
            is CallableDescriptor -> descriptor.returnType
            is ValueDescriptor -> descriptor.type
            else -> return
        } ?: return
        if ((type.isFunctionType ||
                    type.isSuspendFunctionType) &&
            (type.hasAnnotation(InjektFqNames.Given) ||
                    type.hasAnnotation(InjektFqNames.Module) ||
                    type.hasAnnotation(InjektFqNames.GivenSetElement) ||
                    type.hasAnnotation(InjektFqNames.Interceptor)) &&
            type.arguments.dropLast(if (type.hasAnnotation(InjektFqNames.Interceptor)) 2 else 1)
                .any { it.type.contributionKind(declarationStore) == null }) {
            trace.report(
                InjektErrors.NON_GIVEN_PARAMETER_ON_GIVEN_DECLARATION
                    .on(
                        declaration,
                        when {
                            type.hasAnnotation(InjektFqNames.Given) -> InjektFqNames.Given.shortName()
                            type.hasAnnotation(InjektFqNames.Module) -> InjektFqNames.Module.shortName()
                            type.hasAnnotation(InjektFqNames.GivenSetElement) -> InjektFqNames.GivenSetElement.shortName()
                            type.hasAnnotation(InjektFqNames.Interceptor) -> InjektFqNames.Interceptor.shortName()
                            else -> error("")
                        }
                    )
            )
        }
    }

    private fun List<ParameterDescriptor>.checkParameters(
        declaration: KtDeclaration,
        descriptor: DeclarationDescriptor,
        trace: BindingTrace,
    ) {
        if (descriptor.hasAnnotation(InjektFqNames.Given) ||
            descriptor.hasAnnotation(InjektFqNames.Module) ||
            declaration.hasAnnotation(InjektFqNames.GivenSetElement) ||
            (descriptor is ConstructorDescriptor && (
                    descriptor.constructedClass.hasAnnotation(InjektFqNames.Given) ||
                            descriptor.hasAnnotation(InjektFqNames.Module) ||
                            declaration.hasAnnotation(InjektFqNames.GivenSetElement)))
        ) {
            this
                .filter {
                    it.contributionKind(declarationStore) == null &&
                            it.type.contributionKind(declarationStore) == null
                }
                .forEach {
                    trace.report(
                        InjektErrors.NON_GIVEN_PARAMETER_ON_GIVEN_DECLARATION
                            .on(
                                it.findPsi() ?: declaration,
                                when {
                                    descriptor.hasAnnotation(InjektFqNames.Given) -> InjektFqNames.Given.shortName()
                                    descriptor.hasAnnotation(InjektFqNames.Module) -> InjektFqNames.Module.shortName()
                                    descriptor.hasAnnotation(InjektFqNames.GivenSetElement) -> InjektFqNames.GivenSetElement.shortName()
                                    else -> error("")
                                }
                            )
                    )
                }
        }
    }
}
