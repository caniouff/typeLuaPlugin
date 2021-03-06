/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
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

package com.tang.intellij.lua.ty

import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.ext.recursionGuard
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.impl.LuaNameExprMixin
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaClassMemberIndex
import org.luaj.vm2.Lua

fun inferExpr(expr: LuaExpr?, context: SearchContext): ITy {
    return when (expr) {
        is LuaUnaryExpr -> expr.infer(context)
        is LuaBinaryExpr -> expr.infer(context)
        is LuaCallExpr -> expr.infer(context)
        is LuaClosureExpr -> infer(expr, context)
        is LuaTableExpr -> expr.infer()
        is LuaParenExpr -> infer(expr.expr, context)
        is LuaNameExpr -> expr.infer(context)
        is LuaLiteralExpr -> expr.infer()
        is LuaIndexExpr -> expr.infer(context)
        null -> Ty.UNKNOWN
        else -> Ty.UNKNOWN
    }
}

private fun LuaUnaryExpr.infer(context: SearchContext): ITy {
    val stub = stub
    val operator = if (stub != null) stub.opType else unaryOp.node.firstChildNode.elementType

    return when (operator) {
        LuaTypes.MINUS -> infer(expr, context) // Negative something
        LuaTypes.GETN -> Ty.NUMBER // Table length is a number
        else -> Ty.UNKNOWN
    }
}

private fun LuaBinaryExpr.infer(context: SearchContext): ITy {
    val stub = stub
    val operator = if (stub != null) stub.opType else {
        val firstChild = firstChild
        val nextVisibleLeaf = PsiTreeUtil.nextVisibleLeaf(firstChild)
        nextVisibleLeaf?.node?.elementType
    }
    var ty: ITy = Ty.UNKNOWN
    operator.let {
        ty = when (it) {
            LuaTypes.LE -> guessLessThenType(this, context)
        //..
            LuaTypes.CONCAT -> Ty.STRING
        //<=, ==, <, ~=, >=, >
            LuaTypes.LE, LuaTypes.EQ, LuaTypes.LT, LuaTypes.NE, LuaTypes.GE, LuaTypes.GT -> Ty.BOOLEAN
        //and, or
            LuaTypes.AND, LuaTypes.OR -> guessAndOrType(this, operator, context)
        //&, <<, |, >>, ~, ^,    +, -, *, /, //, %
            LuaTypes.BIT_AND, LuaTypes.BIT_LTLT, LuaTypes.BIT_OR, LuaTypes.BIT_RTRT, LuaTypes.BIT_TILDE, LuaTypes.EXP,
            LuaTypes.PLUS, LuaTypes.MINUS, LuaTypes.MULT, LuaTypes.DIV, LuaTypes.DOUBLE_DIV, LuaTypes.MOD -> guessBinaryOpType(this, context)
            else -> Ty.UNKNOWN
        }
    }
    return ty
}

private fun getBinaryExprOpType(binaryExpr: LuaBinaryExpr):IElementType? {
    val stub = binaryExpr.stub
    return if (stub != null) stub.opType else {
        val firstChild = binaryExpr.firstChild
        val nextVisibleLeaf = PsiTreeUtil.nextVisibleLeaf(firstChild)
        nextVisibleLeaf?.node?.elementType
    }
}

private fun guessLessThenType(binaryExpr: LuaBinaryExpr, context: SearchContext):ITy {
    val leftType = binaryExpr.left?.guessType(context)
    val rightType = binaryExpr.right?.guessType(context)
    if (leftType is TyFuncDef && rightType is TyFunction) {
        return TyFuncDefPsiFunction(leftType, rightType)
    }
    return Ty.BOOLEAN
}

private fun guessAndOrType(binaryExpr: LuaBinaryExpr, operator: IElementType?, context:SearchContext): ITy {
    val rhs = binaryExpr.right
    //and
    if (operator == LuaTypes.AND)
        return infer(rhs, context)

    //or
    val lhs = binaryExpr.left
    val lty = infer(lhs, context)
    return if (rhs != null) lty.union(infer(rhs, context)) else lty
}

private fun guessBinaryOpType(binaryExpr : LuaBinaryExpr, context:SearchContext): ITy {
    val lhs = binaryExpr.left
    // TODO: Search for operator overrides
    return infer(lhs, context)
}

fun LuaCallExpr.createSubstitutor(sig: IFunSignature, context: SearchContext): ITySubstitutor? {
    if (sig.isGeneric()) {
        val list = this.argList.map { it.guessType(context.clone()) }
        val map = mutableMapOf<String, ITy>()
        var processedIndex = -1
        sig.tyParameters.forEach { map[it.name] = Ty.UNKNOWN }
        sig.processArgs(this) { index, param ->
            val arg = list.getOrNull(index)
            if (arg != null) {
                GenericAnalyzer(arg, param.ty).analyze(map)
            }
            processedIndex = index
            true
        }
        // vararg
        val varargTy = sig.varargTy
        if (varargTy != null && processedIndex < list.lastIndex) {
            val argTy = list[processedIndex + 1]
            GenericAnalyzer(argTy, varargTy).analyze(map)
        }
        sig.tyParameters.forEach {
            val superCls = it.superClassName
            if (Ty.isInvalid(map[it.name]) && superCls != null) map[it.name] = Ty.create(superCls)
        }
        return object : TySubstitutor() {
            override fun substitute(clazz: ITyClass): ITy {
                return map.getOrElse(clazz.className) { clazz }
            }
        }
    }
    return null
}

private fun LuaCallExpr.getReturnTy(sig: IFunSignature, context: SearchContext): ITy? {
    var resultSig = sig
    val substitutor = createSubstitutor(sig, context)
    if (substitutor != null) {
        resultSig = sig.substitute(substitutor)
    }
    val returnTy = resultSig.returnTy
    return if (returnTy is TyTuple) {
        if (context.guessTuple())
            returnTy
        else returnTy.list.getOrNull(context.index)
    } else {
        if (context.guessTuple() || context.index == 0)
            returnTy
        else null
    }
}

private fun LuaCallExpr.infer(context: SearchContext): ITy {
    val luaCallExpr = this
    // xxx()
    val expr = luaCallExpr.expr
    val exprType = expr.guessType(context)
    if (TyUnion.has(exprType, TyStruct::class.java) && !TyUnion.has(exprType, TyFuncDef::class.java)) {
        return exprType
    }
    // 从 require 'xxx' 中获取返回类型

    if (expr is LuaNameExpr && Constants.IsImportPakWord(expr.name)) {
        var filePath: String? = null
        val string = luaCallExpr.firstStringArg
        if (string is LuaLiteralExpr) {
            filePath = string.stringValue
        }
        var file: LuaPsiFile? = null
        if (filePath != null)
            file = resolveRequireFile(filePath, luaCallExpr.project)
        if (file != null)
            return file.guessType(context)

        return Ty.UNKNOWN
    } else if (expr is LuaNameExpr && Constants.IsStructOrInterfaceDefWord(expr.name)) {
        val argsExpr = luaCallExpr.args
        if (argsExpr is LuaSingleArg) {
            val assignStat = PsiTreeUtil.getParentOfType(this, LuaAssignStat::class.java)
            val nameExpr = assignStat?.varExprList?.exprList?.get(0)
            if (nameExpr != null && nameExpr is LuaNameExpr) {
                var tyStruct = TyStruct(nameExpr)
                tyStruct.isInterface = (expr.name == Constants.WORD_INTERFACE)
                return tyStruct
            }
        }
    } else if (expr is LuaNameExpr && expr.name == Constants.WORD_FUNCDEF) {
        val argsExpr = luaCallExpr.args
        val binaryExpr = PsiTreeUtil.getParentOfType(this, LuaBinaryExpr::class.java)
        if (argsExpr is LuaListArgs) {
            if (argsExpr.exprList.size == 1) {
                val arg = argsExpr.exprList[0]
                val def = TyFuncDef()
                if (binaryExpr!= null && LuaTypes.LE == getBinaryExprOpType(binaryExpr)) {
                    def.setParamsOrReturns(args, context)
                } else if (arg.name == "_") {//module or global
                    def.moduleName = expr.moduleName
                    def.global = expr.moduleName == null
                } else {
                    def.receiver = arg.guessType(context)
                }
                return def
            }
        }
    }else if(expr is LuaNameExpr && expr.name == Constants.WORD_FUNC_DECLARE) {
        val argsExpr = luaCallExpr.args

        val def = TyFuncDef()
        val argInfoList = ArrayList<LuaParamInfo>()
        if (argsExpr is LuaListArgs) {
            val argTypeList = ArrayList<ITy>()
            argsExpr.exprList.forEach {
                if (it is LuaIndexExpr) {
                    val parantIndexExpr = it.prefixExpr
                    val param = LuaParamInfo()
                    param.ty = parantIndexExpr.guessType(context)
                    param.name = it.name ?: ""
                    param.isSelf = false
                    argInfoList.add(param)

                    argTypeList.add(param.ty)
                }
            }
            def.setParamsType(TyTuple(argTypeList))
        } else {
            def.setParamsType(Ty.VOID)
        }
        def.setParamsLuaInfo(argInfoList)
        return def

    } else if (expr is LuaNameExpr && expr.name == Constants.WORD_MAP) {
        val argsExpr = luaCallExpr.args

        if (argsExpr is LuaListArgs) {
            var params = mutableListOf<ITy>()
            argsExpr.exprList.forEach {
                val tyBuildIn = if (it is LuaLiteralExpr && it.kind == LuaLiteralKind.String) Ty.getBuiltin(it.text.trim('\"')) else null
                if (tyBuildIn != null) {
                    params.add(tyBuildIn)
                } else {
                    params.add(it.guessType(context))
                }
            }

            var base = TyPrimitive(TyPrimitiveKind.String, Constants.WORD_MAP)
            return TySerializedGeneric(params.toTypedArray(), base)
        }
    } else if (expr is LuaNameExpr && expr.name == Constants.WORD_LIST) {
        val argsExpr = luaCallExpr.args
        if (argsExpr is LuaListArgs && argsExpr.exprList.size == 1) {
            val listTypeExpr = argsExpr.exprList[0]
            if (listTypeExpr is LuaLiteralExpr && listTypeExpr.kind == LuaLiteralKind.String) {
                val buildInTy = Ty.getBuiltin(listTypeExpr.text.trim('\"'))
                if (buildInTy != null) {
                    return TyArray(buildInTy)
                }
            }
            var base = listTypeExpr.guessType(context)
            return TyArray(base)
        }
    } else if (expr is LuaNameExpr && expr.name == Constants.WORD_NULLABLE) {
        val argsExpr = luaCallExpr.args
        if (argsExpr is LuaListArgs && argsExpr.exprList.size == 1) {
            val listTypeExpr = argsExpr.exprList[0]
            return listTypeExpr.guessType(context)
        }
    } else if (expr is LuaNameExpr && expr.name == Constants.WORD_ENUM) {
        val argsExpr = luaCallExpr.args
        if (argsExpr is LuaSingleArg) {
            val assignStat = PsiTreeUtil.getParentOfType(this, LuaAssignStat::class.java)
            val nameExpr = assignStat?.varExprList?.exprList?.get(0)
            if (nameExpr != null && nameExpr is LuaNameExpr) {
                return TyEnum(nameExpr)
            }
        }
    }

    var ret: ITy = Ty.UNKNOWN
    val ty = infer(expr, context)//expr.guessType(context)
    TyUnion.each(ty) {
        when (it) {
            is ITyFunction -> {
                it.process(Processor { sig ->
                    val targetTy = getReturnTy(sig, context)

                    if (targetTy != null)
                        ret = ret.union(targetTy)
                    true
                })
            }
            is TyFuncDef -> {
                it.setParamsOrReturns(luaCallExpr.args, context)
                ret = if (it.hasParamsInfo()) {
                    TyFuncDeclarePsiFunction(it)
                } else {
                    val funDef = it.union(it.toFuncDefReceiver())
                    ret.union(funDef)
                }
            }
            //constructor : Class table __call
            is ITyClass -> ret = ret.union(it)
        }
    }

    // xxx.new()
    if (expr is LuaIndexExpr) {
        val fnName = expr.name
        if (fnName != null && LuaSettings.isConstructorName(fnName)) {
            ret = ret.union(expr.guessParentType(context))
        }
    }

    return ret
}

private fun LuaNameExpr.infer(context: SearchContext): ITy {
    val set = recursionGuard(this, Computable {
        var type:ITy = Ty.UNKNOWN
        val multiResolve = multiResolve(this, context)
        multiResolve.forEach {
            val set = getType(context, it)
            type = type.union(set)
        }

        /**
         * fixme : optimize it.
         * function xx:method()
         *     self.name = '123'
         * end
         *
         * https://github.com/EmmyLua/IntelliJ-EmmyLua/issues/93
         * the type of 'self' should be same of 'xx'
         */
        if (Ty.isInvalid(type)) {
            if (name == Constants.WORD_SELF) {
                val methodDef = PsiTreeUtil.getStubOrPsiParentOfType(this, LuaClassMethodDef::class.java)
                if (methodDef != null && !methodDef.isStatic) {
                    val methodName = methodDef.classMethodName
                    val expr = methodName.expr
                    type = expr.guessType(context)
                }
            }
        }

        if (Ty.isInvalid(type)) {
            type = getType(context, this)
        }

        type
    })
    return set ?: Ty.UNKNOWN
}

private fun getType(context: SearchContext, def: PsiElement): ITy {
    when (def) {
        is LuaNameExpr -> {
            //todo stub.module -> ty
            val stub = def.stub
            stub?.module?.let {
                val memberType = createSerializedClass(it).findMemberType(def.name, context)
                if (memberType != null && !Ty.isInvalid(memberType))
                    return memberType
            }

            var type: ITy = def.docTy ?: Ty.UNKNOWN
            //guess from value expr
            if (Ty.isInvalid(type)) {
                val stat = def.assignStat
                if (stat != null) {
                    val exprList = stat.valueExprList
                    if (exprList != null) {
                        type = context.withIndex(stat.getIndexFor(def)) {
                            exprList.guessTypeAt(context)
                        }
                    }
                }
            }

            return type
        }
        is LuaTypeGuessable -> return def.guessType(context)
        else -> return Ty.UNKNOWN
    }
}

private fun isGlobal(nameExpr: LuaNameExpr):Boolean {
    val minx = nameExpr as LuaNameExprMixin
    val gs = minx.greenStub
    return gs?.isGlobal ?: (resolveLocal(nameExpr, null) == null)
}

private fun LuaLiteralExpr.infer(): ITy {
    return when (this.kind) {
        LuaLiteralKind.Bool -> Ty.BOOLEAN
        LuaLiteralKind.String -> Ty.STRING
        LuaLiteralKind.Number -> Ty.NUMBER
        LuaLiteralKind.Varargs -> {
            val o = PsiTreeUtil.getParentOfType(this, LuaFuncBodyOwner::class.java)
            o?.varargType ?: Ty.UNKNOWN
        }
        //LuaLiteralKind.Nil -> Ty.NIL
        else -> Ty.UNKNOWN
    }
}

private fun LuaIndexExpr.infer(context: SearchContext): ITy {
    val retTy = recursionGuard(this, Computable {
        val indexExpr = this

        var parentTy: ITy? = null
        // xxx[yyy] as an array element?
        if (indexExpr.brack) {
            val tySet = indexExpr.guessParentType(context)
            var ty: ITy = Ty.UNKNOWN

            // Type[]
            TyUnion.each(tySet) {
                if (it is ITyArray) ty = ty.union(it.base)
            }
            if (ty !is TyUnknown) return@Computable ty

            // table<number, Type>
            TyUnion.each(tySet) {
                if (it is ITyGeneric) ty = ty.union(it.getParamTy(1))
            }
            if (ty !is TyUnknown) return@Computable ty

            parentTy = tySet
        }

        //from @type annotation
        val docTy = indexExpr.docTy
        if (docTy != null)
            return@Computable docTy

        // xxx.yyy = zzz
        //from value
        var result: ITy = Ty.UNKNOWN
        val assignStat = indexExpr.assignStat
        if (assignStat != null) {
            result = context.withIndex(assignStat.getIndexFor(indexExpr)) {
                assignStat.valueExprList?.guessTypeAt(context) ?: Ty.UNKNOWN
            }
        }

        val parentType = indexExpr.guessParentType(context)
        val funcDef = TyUnion.find(parentType, TyFuncDef::class.java)
        if (funcDef != null && result is TyFunction) {
            return@Computable TyFuncDefPsiFunction(funcDef, result)
        }
        val tyStruct = TyUnion.find(parentType, TyStruct::class.java)
        if (tyStruct != null) { //Struct can not assign field
            result = Ty.UNKNOWN
        }

        //from other class member
        val propName = indexExpr.name
        if (propName != null) {
            val prefixType = parentTy ?: indexExpr.guessParentType(context)

            prefixType.eachTopClass(Processor {
                result = result.union(guessFieldType(propName, it, context))
                true
            })
        }
        result
    })

    return retTy ?: Ty.UNKNOWN
}

private fun guessFieldType(fieldName: String, type: ITyClass, context: SearchContext): ITy {
    // _G.var = {}  <==>  var = {}
    if (type.className == Constants.WORD_G)
        return TyClass.createGlobalType(fieldName)

    var set:ITy = Ty.UNKNOWN

    LuaClassMemberIndex.processAll(type, fieldName, context, Processor {
        set = set.union(it.guessType(context))
        true
    })

    return set
}

private fun LuaTableExpr.infer(): ITy {
    val list = this.tableFieldList
    if (list.size == 1) {
        val valueExpr = list.first().valueExpr
        if (valueExpr is LuaLiteralExpr && valueExpr.kind == LuaLiteralKind.Varargs) {
            val func = PsiTreeUtil.getStubOrPsiParentOfType(this, LuaFuncBodyOwner::class.java)
            val ty = func?.varargType
            if (ty != null)
                return TyArray(ty)
        }
    }
    return TyTable(this)
}