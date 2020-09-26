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

package com.tang.intellij.lua.codeInsight

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Function
import com.intellij.util.FunctionUtil
import com.intellij.util.Query
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.comment.psi.LuaDocTagClass
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.search.LuaClassInheritorsSearch
import com.tang.intellij.lua.psi.search.LuaOverridingMethodsSearch
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaClassMemberIndex
import com.tang.intellij.lua.stubs.index.LuaInterfaceNameIndex
import com.tang.intellij.lua.stubs.index.LuaStructNameIndex
import com.tang.intellij.lua.ty.*
import com.twelvemonkeys.util.CollectionUtil

/**
 * line marker
 * Created by tangzx on 2016/12/11.
 */
class LuaLineMarkerProvider(private val daemonSettings: DaemonCodeAnalyzerSettings, private val colorsManager:EditorColorsManager) : LineMarkerProvider {

    var elementsMarkMap = HashMap<PsiElement, Boolean>()
    private fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in LineMarkerInfo<*>>) {
        if (elementsMarkMap.containsKey(element)) {
            return
        }
        elementsMarkMap[element] = true
        if (element is LuaClassMethodName) {
            val methodDef = PsiTreeUtil.getParentOfType(element, LuaClassMethod::class.java)!!
            val project = methodDef.project
            val context = SearchContext(project)
            val type = methodDef.guessClassType(context)

            //OverridingMethod
            val classMethodNameId = element.id
            if (type != null && classMethodNameId != null) {
                val methodName = methodDef.name!!
                var superType = type.getSuperClass(context)

                while (superType != null && superType is TyClass) {
                    ProgressManager.checkCanceled()
                    val superTypeName = superType.className
                    val superMethod = LuaClassMemberIndex.findMethod(superTypeName, methodName, context)
                    if (superMethod != null) {
                        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                                .setTargets(superMethod)
                                .setTooltipText("Overrides function in $superTypeName")
                        result.add(builder.createLineMarkerInfo(classMethodNameId))
                        break
                    }
                    superType = superType.getSuperClass(context)
                }
            }

            // OverridenMethod
            val search = LuaOverridingMethodsSearch.search(methodDef)
            if (search.findFirst() != null && classMethodNameId != null) {
                result.add(LineMarkerInfo(classMethodNameId,
                        classMethodNameId.textRange,
                        AllIcons.Gutter.OverridenMethod,
                        Pass.LINE_MARKERS,
                        null,
                        object : LuaLineMarkerNavigator<PsiElement, LuaClassMethod>() {

                            override fun getTitle(elt: PsiElement)
                                    = "Choose Overriding Method of ${methodDef.name}"

                            override fun search(elt: PsiElement)
                                    = LuaOverridingMethodsSearch.search(methodDef)
                        },
                        GutterIconRenderer.Alignment.CENTER))
            }

            //line separator
            if (daemonSettings.SHOW_METHOD_SEPARATORS) {
                //todo : module file method
                val anchor = PsiTreeUtil.firstChild(methodDef)
                val lineSeparator = LineMarkerInfo(anchor,
                        anchor.textRange,
                        null,
                        Pass.LINE_MARKERS,
                        null,
                        null,
                        GutterIconRenderer.Alignment.RIGHT)
                lineSeparator.separatorColor = colorsManager.globalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
                lineSeparator.separatorPlacement = SeparatorPlacement.TOP
                result.add(lineSeparator)
            }
        } else if (element is LuaDocTagClass) {
            val classType = element.type
            val project = element.getProject()
            val query = LuaClassInheritorsSearch.search(GlobalSearchScope.allScope(project), project, classType.className)
            if (query.findFirst() != null) {
                val id = element.id
                result.add(LineMarkerInfo(id,
                        id.textRange,
                        AllIcons.Gutter.OverridenMethod,
                        Pass.LINE_MARKERS,
                        Function<PsiElement, String> { element.name },
                        object : LuaLineMarkerNavigator<PsiElement, LuaDocTagClass>() {
                            override fun getTitle(elt: PsiElement)
                                    = "Choose Subclass of ${element.name}"

                            override fun search(elt: PsiElement): Query<LuaDocTagClass> {
                                return LuaClassInheritorsSearch.search(GlobalSearchScope.allScope(project), project, element.name)
                            }
                        },
                        GutterIconRenderer.Alignment.CENTER))
            }

            // class 标记
            val id = element.id
            val startOffset = id.textOffset
            val classIcon = LineMarkerInfo(id,
                    TextRange(startOffset, startOffset),
                    LuaIcons.CLASS,
                    Pass.LINE_MARKERS, null, null,
                    GutterIconRenderer.Alignment.CENTER)
            result.add(classIcon)
        } else if (element is LuaCallExpr) {
            val expr = element.expr

            val reference = expr.reference
            if (reference != null) {
                val resolve = reference.resolve()
                if (resolve != null) {
                    var cur: PsiElement? = element
                    while (cur != null) {
                        ProgressManager.checkCanceled()
                        val bodyOwner = PsiTreeUtil.getParentOfType(cur, LuaFuncBodyOwner::class.java)
                        if (bodyOwner === resolve) {
                            val anchor = PsiTreeUtil.firstChild(element)
                            result.add(LineMarkerInfo<PsiElement>(anchor,
                                    anchor.textRange,
                                    AllIcons.Gutter.RecursiveMethod,
                                    Pass.LINE_MARKERS,
                                    FunctionUtil.constant("Recursive call"),
                                    null,
                                    GutterIconRenderer.Alignment.CENTER))
                            break
                        }
                        cur = bodyOwner
                    }
                }
            }

            if (expr.text == Constants.WORD_FUNCDEF) {
                val project = element.getProject()
                val context = SearchContext(project)
                val argsExpr = element.args
                var receiverType : TyStruct? = null
                if (argsExpr is LuaListArgs) {
                    if (argsExpr.exprList.size == 1) {
                        val arg = argsExpr.exprList[0]
                        val guessType = arg.guessType(context)
                        receiverType = TyUnion.find(guessType, TyStruct::class.java)
                    }
                }
                val luaIndexExpr = LuaPsiTreeUtil.getParentOfType(element, LuaIndexExpr::class.java, LuaCallExpr::class.java)
                if (receiverType != null && luaIndexExpr != null) {
                    if (receiverType.isInterface) {
                        val structTargets = collectImplementInterfaceStructs(receiverType, project, context)
                        val funcTargets = collectFunInStructs(structTargets, luaIndexExpr.name ?: "", context)
                        if (funcTargets.size > 0) {
                            val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                                    .setTargets(funcTargets)
                                    .setTooltipText("Implement method")
                            result.add(builder.createLineMarkerInfo(expr))
                        }
                    } else {
                        val interfaceTargets = collectStructImplementInterfaces(receiverType, project, context)
                        val funcTargets = collectFunInStructs(interfaceTargets, luaIndexExpr.name ?: "", context)
                        if (funcTargets.size > 0) {
                            val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                                    .setTargets(funcTargets)
                                    .setTooltipText("Implement Interface")
                            result.add(builder.createLineMarkerInfo(expr))
                        }
                    }
                }
            } else if (expr.text == Constants.WORD_STRUCT) {
                val project = element.getProject()
                val context = SearchContext(project)
                val tyStruct = TyUnion.find(element.guessType(context), TyStruct::class.java)
                if (tyStruct is TyStruct) {
                    val interfaceTargets = collectStructImplementInterfaces(tyStruct, project, context)
                    if (interfaceTargets.size > 0) {
                        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                                .setTargets(interfaceTargets)
                                .setTooltipText("Implement Interface")
                        result.add(builder.createLineMarkerInfo(expr))
                    }
                }
            } else if (expr.text == Constants.WORD_INTERFACE) {
                val project = element.getProject()
                val context = SearchContext(project)
                val tyInterface = TyUnion.find(element.guessType(context), TyStruct::class.java)
                if (tyInterface is TyStruct) {
                    val structTargets = collectImplementInterfaceStructs(tyInterface, project, context)
                    if (structTargets.size > 0) {
                        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                                .setTargets(structTargets)
                                .setTooltipText("Implement struct")
                        result.add(builder.createLineMarkerInfo(expr))
                    }
                }
            }
        } else if (element is LuaReturnStat) {
            val exprList = element.exprList
            if (exprList != null) {
                for (psiElement in exprList.children) {
                    if (psiElement is LuaCallExpr) {
                        val returnKeyWord = element.firstChild
                        result.add(LineMarkerInfo(returnKeyWord,
                                returnKeyWord.textRange,
                                AllIcons.General.HideRightHover,
                                Pass.LINE_MARKERS,
                                FunctionUtil.constant("Tail call"), null,
                                GutterIconRenderer.Alignment.CENTER))
                        break
                    }
                }
            }
        }  else if (element is LuaTableField) {
            val callExpr = LuaPsiTreeUtil.resolveParentOfType(element, LuaCallExpr::class.java)
            if (callExpr?.expr?.text == Constants.WORD_INTERFACE) {
                val project = element.getProject()
                val context = SearchContext(project)
                val tyInterface = TyUnion.find(callExpr.guessType(context), TyStruct::class.java)
                if (tyInterface is TyStruct) {
                    val structTargets = collectImplementInterfaceStructs(tyInterface, project, context)
                    val funcTargets = collectFunInStructs(structTargets, element.fieldName?: "", context)
                    if (funcTargets.size > 0) {
                        val builder = NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                                .setTargets(funcTargets)
                                .setTooltipText("Implement method")
                        result.add(builder.createLineMarkerInfo(element))
                    }
                }
            }
        }
    }

    override fun getLineMarkerInfo(psiElement: PsiElement): LineMarkerInfo<*>? {
        return null
    }

    override fun collectSlowLineMarkers(list: List<PsiElement>, collection: MutableCollection<LineMarkerInfo<*>>) {
        elementsMarkMap.clear()
        for (element in list) {
            ProgressManager.checkCanceled()
            collectNavigationMarkers(element, collection)
        }
    }

    private fun collectStructImplementInterfaces(tyStruct: TyStruct, project: Project, context: SearchContext): ArrayList<LuaTypeGuessable> {
        val interfaceTargets = ArrayList<LuaTypeGuessable>()
        LuaInterfaceNameIndex.instance.processAllKeys(project) {
            val all = LuaInterfaceNameIndex.instance.get(it, context.project, context.getScope())
            all.forEach {member->
                var tyInterface :ITy? = member.guessType(context)
                if (tyInterface != null) {
                    TyUnion.each(tyInterface) {tyClass->
                        if (tyClass is ITyClass && tyStruct.isImplementTo(tyClass, context)) {
                            interfaceTargets.add(member)
                        }
                    }
                }

            }
            true
        }
        return interfaceTargets
    }

    private fun collectImplementInterfaceStructs(tyInteface: TyStruct, project: Project, context: SearchContext): ArrayList<LuaTypeGuessable> {
        val structTargets = ArrayList<LuaTypeGuessable>()
        LuaStructNameIndex.instance.processAllKeys(project) {
            val all = LuaStructNameIndex.instance.get(it, context.project, context.getScope())
            all.forEach {member->
                var tyStruct :ITy? = member.guessType(context)
                if (tyStruct != null) {
                    TyUnion.each(tyStruct) {tyClass->
                        if (tyClass is TyStruct && tyClass.isImplementTo(tyInteface, context)) {
                            structTargets.add(member)
                        }
                    }
                }

            }
            true
        }
        return structTargets
    }

    private fun collectFunInStructs(tyStructs : ArrayList<LuaTypeGuessable>, name : String, context: SearchContext) : ArrayList<LuaTypeGuessable> {
        val funTargets = ArrayList<LuaTypeGuessable>()
        if (name != "") {
            tyStructs.forEach {
                var tyClass :ITy? = it.guessType(context)
                if (tyClass != null) {
                    TyUnion.each(tyClass) { tyStruct ->
                        if (tyStruct is ITyClass) {
                            val func = tyStruct.findMember(name, context)
                            if (func != null) {
                                funTargets.add(func)
                            }
                        }
                    }
                }
            }
        }
        return funTargets
    }
}
