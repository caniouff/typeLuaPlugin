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

package com.tang.intellij.lua.stubs

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.*
import com.intellij.util.io.StringRef
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.impl.LuaNameExprImpl
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaClassMemberIndex
import com.tang.intellij.lua.stubs.index.StubKeys
import com.tang.intellij.lua.ty.ITy
import com.tang.intellij.lua.ty.TyStruct
import com.tang.intellij.lua.ty.TyUnion

/**
 * name expr stub
 * Created by TangZX on 2017/4/12.
 */
class LuaNameExprType : LuaStubElementType<LuaNameExprStub, LuaNameExpr>("NAME_EXPR") {

    override fun createPsi(luaNameStub: LuaNameExprStub) = LuaNameExprImpl(luaNameStub, this)

    override fun shouldCreateStub(node: ASTNode): Boolean {
        return createStubIfParentIsStub(node)
    }

    override fun createStub(luaNameExpr: LuaNameExpr, stubElement: StubElement<*>): LuaNameExprStub {
        val psiFile = luaNameExpr.containingFile
        val name = luaNameExpr.name
        val module = if (psiFile is LuaPsiFile) psiFile.moduleName ?: Constants.WORD_G else Constants.WORD_G
        val searchContext = SearchContext(luaNameExpr.project, psiFile)
        val isGlobal = resolveLocal(luaNameExpr, searchContext) == null

        val stat = luaNameExpr.assignStat
        val docTy = stat?.comment?.ty
        var structType = Constants.ST_NONE

        val guessType = luaNameExpr.guessType(searchContext)
        TyUnion.each(guessType) {
            if (it is TyStruct) {
                structType = if(it.isInterface) Constants.ST_INTERFACE else Constants.ST_IMPLEMENT
            }
        }
        return LuaNameExprStubImpl(name,
                module,
                stat != null,
                isGlobal,
                structType,
                docTy,
                stubElement,
                this)
    }

    override fun serialize(stub: LuaNameExprStub, stream: StubOutputStream) {
        stream.writeName(stub.name)
        stream.writeName(stub.module)
        stream.writeBoolean(stub.isName)
        stream.writeBoolean(stub.isGlobal)
        stream.writeInt(stub.structType)
        stream.writeTyNullable(stub.docTy)
    }

    override fun deserialize(stream: StubInputStream, stubElement: StubElement<*>): LuaNameExprStub {
        val nameRef = stream.readName()
        val moduleRef = stream.readName()
        val isName = stream.readBoolean()
        val isGlobal = stream.readBoolean()
        val structType = stream.readInt()
        val docTy = stream.readTyNullable()
        return LuaNameExprStubImpl(StringRef.toString(nameRef),
                StringRef.toString(moduleRef),
                isName,
                isGlobal,
                structType,
                docTy,
                stubElement,
                this)
    }

    override fun indexStub(luaNameStub: LuaNameExprStub, indexSink: IndexSink) {
        if (luaNameStub.isGlobal &&luaNameStub.isName) {
            val module = luaNameStub.module

            LuaClassMemberIndex.indexStub(indexSink, module, luaNameStub.name)

            indexSink.occurrence(StubKeys.SHORT_NAME, luaNameStub.name)

            if (luaNameStub.structType == Constants.ST_IMPLEMENT) {
                indexSink.occurrence(StubKeys.STRUCT, luaNameStub.name)
            } else if(luaNameStub.structType == Constants.ST_INTERFACE) {
                indexSink.occurrence(StubKeys.INTERFACE, luaNameStub.name)
            }
        }
    }
}

interface LuaNameExprStub : LuaExprStub<LuaNameExpr>, LuaDocTyStub {
    val name: String
    val module: String
    val isName: Boolean
    val isGlobal: Boolean
    val structType: Int
}

class LuaNameExprStubImpl(
        override val name: String,
        override val module: String,
        override val isName: Boolean,
        override val isGlobal: Boolean,
        override val structType: Int,
        override val docTy: ITy?,
        parent: StubElement<*>,
        elementType: LuaStubElementType<*, *>
) : LuaStubBase<LuaNameExpr>(parent, elementType), LuaNameExprStub