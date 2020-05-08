package com.ivianuu.injekt.compiler

import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name

object InjektNameConventions {
    fun getProviderNameForClass(className: Name): Name {
        return Name.identifier("${className}_Provider")
    }

    fun getMembersInjectorNameForClass(className: Name): Name {
        return Name.identifier("${className}_MembersInjector")
    }

    fun getModuleClassNameForModuleFunction(moduleFunction: IrFunction): Name {
        return Name.identifier(
            "${moduleFunction.name.asString()
                .capitalize()}${moduleFunction.valueParametersHash()}_Impl"
        )
    }

    fun getImplNameForFactoryFunction(factoryFunction: IrFunction): Name {
        return Name.identifier(
            "${factoryFunction.name.asString()
                .capitalize()}${factoryFunction.valueParametersHash()}_Impl"
        )
    }

    fun getModuleNameForFactoryFunction(factoryFunction: IrFunction): Name =
        Name.identifier(
            "${factoryFunction.name.asString()
                .capitalize()}${factoryFunction.valueParametersHash()}_Module"
        )

    private fun IrFunction.valueParametersHash(): Int =
        valueParameters.map { it.name.asString() + it.type.render() }.hashCode()
}
