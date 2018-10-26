package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_Type {
    internal abstract fun compile(ctx: ModuleCompilationContext): RType
    internal fun compile(ctx: ExprCompilationContext): RType = compile(ctx.entCtx.modCtx)
}

class S_NameType(val name: String): S_Type() {
    override fun compile(ctx: ModuleCompilationContext): RType = ctx.getType(name)
}

class S_TupleType(val fields: List<Pair<String?, S_Type>>): S_Type() {
    override fun compile(ctx: ModuleCompilationContext): RType {
        val names = mutableSetOf<String>()
        for ((name, _) in fields) {
            if (name != null && !names.add(name)) {
                throw CtError("type_tuple_dupname:$name", "Duplicated field name: '$name'")
            }
        }

        val rFields = fields.map { RTupleField(it.first, it.second.compile(ctx)) }
        return RTupleType(rFields)
    }
}

class S_ListType(val element: S_Type): S_Type() {
    override fun compile(ctx: ModuleCompilationContext): RType {
        val rElement = element.compile(ctx)
        if (rElement == RUnitType) TODO()
        return RListType(rElement)
    }
}
