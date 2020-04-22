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

package com.tang.intellij.lua.stubs.index

import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.stubs.StringStubIndexExtension
import com.tang.intellij.lua.lang.LuaLanguage
import com.tang.intellij.lua.psi.LuaTypeGuessable
import com.tang.intellij.lua.search.SearchContext

class LuaInterfaceNameIndex : StringStubIndexExtension<LuaTypeGuessable>() {

    override fun getVersion(): Int {
        return LuaLanguage.INDEX_VERSION
    }

    override fun getKey() = StubKeys.INTERFACE

    companion object {
        val instance = LuaInterfaceNameIndex()

        fun find(key: String, searchContext: SearchContext): Collection<LuaTypeGuessable> {
            return if (searchContext.isDumb) emptyList() else instance.get(key, searchContext.project, searchContext.getScope())
        }
    }
}
