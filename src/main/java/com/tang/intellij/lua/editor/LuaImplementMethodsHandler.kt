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

package com.tang.intellij.lua.editor

import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.navigation.BackgroundUpdaterTask
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.ide.util.gotoByName.*
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.presentation.java.SymbolPresentationUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.ArrayUtil
import com.intellij.util.Consumer
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.intellij.util.ui.UIUtil
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.refactoring.generate.ModuleTemplate
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.StubKeys
import com.tang.intellij.lua.ty.*
import javax.swing.Icon
import kotlin.math.max

class DummyBackGroundUpdateTask: BackgroundUpdaterTask {
    constructor() : super(null, "Working...", null)

    override fun getCaption(size: Int): String {
        return ""
    }
}

class StructCellRenderer: DefaultPsiElementCellRenderer() {
    override fun getIcon(element: PsiElement?): Icon {
        return LuaIcons.CLASS
    }

    override fun getContainerText(element: PsiElement?, name: String?): String? {
        return ""
    }
}

class InterfaceChooseContributeModel: ContributorsBasedGotoByModel {
    constructor(project: Project, contributors: Array<out ChooseByNameContributor>) : super(project, contributors) {
        ArrayUtil.append(mSeparators, ".")
    }

    private val mSeparators: Array<String> = ArrayUtil.EMPTY_STRING_ARRAY
    override fun willOpenEditor(): Boolean {
        return false
    }

    override fun saveInitialCheckBoxState(state: Boolean) {}

    override fun getFullName(obj: Any?): String? {
        if (obj is PsiElement) {
            return SymbolPresentationUtil.getSymbolPresentableText(obj)
        }
        return null
    }

    override fun loadInitialCheckBoxState(): Boolean {
        return true
    }

    override fun getPromptText(): String {
        return "Choose interface to implement:"
    }

    override fun getNotInMessage(): String {
        return ""
    }

    override fun getCheckBoxName(): String? {
        return UIUtil.replaceMnemonicAmpersand("No&n-&&project")
    }

    override fun getSeparators(): Array<String> {
        return mSeparators
    }

    override fun getNotFoundMessage(): String {
        return ""
    }
}

class LuaInterfaceContributor : GotoClassContributor, ChooseByNameContributorEx {
    override fun getQualifiedName(navigationItem: NavigationItem): String? = null

    override fun getQualifiedNameSeparator(): String? {
        return "."
    }

    override fun processNames(processor: Processor<String>, searchScope: GlobalSearchScope, idFilter: IdFilter?) {
        ProgressManager.checkCanceled()
        StubIndex.getInstance().processAllKeys(StubKeys.INTERFACE, processor, searchScope, idFilter)
    }

    override fun processElementsWithName(name: String, processor: Processor<NavigationItem>, parameters: FindSymbolParameters) {
        ProgressManager.checkCanceled()
        StubIndex.getInstance().processElements(StubKeys.INTERFACE, name, parameters.project, parameters.searchScope,
                parameters.idFilter, LuaTypeGuessable::class.java, processor)
    }

    override fun getItemsByName(name: String?, pattern: String?, project: Project?, includeNonProjectItems: Boolean): Array<NavigationItem> {
        return NavigationItem.EMPTY_NAVIGATION_ITEM_ARRAY
    }

    override fun getNames(project: Project?, includeNonProjectItems: Boolean): Array<String> {
        return ArrayUtil.EMPTY_STRING_ARRAY
    }
}
class LuaImplementMethodsHandler : LanguageCodeInsightActionHandler {
    companion object {
        val DEFAULT_CELL_RENDERER = DefaultPsiElementCellRenderer()
        val STRUCT_CELL_RENDERER = StructCellRenderer()
    }
    override fun isValidFor(editor: Editor?, file: PsiFile?): Boolean {
        return file is LuaPsiFile
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val psiElementList = findStructInFile(project, editor, file)

        val popup = PsiElementListNavigator.navigateOrCreatePopup(psiElementList.toTypedArray(), "Choose Type", null,
                STRUCT_CELL_RENDERER, DummyBackGroundUpdateTask(), Consumer {
            if (it.size == 1 && it[0] is LuaTypeGuessable) {
                val element = it[0] as LuaTypeGuessable
                chooseInterface(project, editor, file, element)
            }
        })

        popup?.let { popup.showInBestPositionFor(editor) }
    }

    private fun findStructAtCaret(editor: Editor, file: PsiFile): TyStruct? {
        return null
    }

    private fun findStructInFile(project: Project, editor: Editor, file: PsiFile):List<NavigatablePsiElement> {
        val caret: Caret = editor.caretModel.primaryCaret
        val offset: Int = caret.offset

        val elementList = ArrayList<NavigatablePsiElement>()
        LuaPsiTreeUtil.walkTopLevelInFile(file.lastChild, LuaAssignStat::class.java) {
            if (it.textOffset < offset && it.valueExprList?.exprList?.size == 1) {
                val valueExpr = it.valueExprList?.exprList?.get(0)
                valueExpr?.let { _ ->
                    if (valueExpr is LuaCallExpr && valueExpr.isFunctionCall &&
                            Constants.IsStructDefWord(valueExpr.expr.name))
                        elementList.add(it.varExprList.exprList[0])
                }
            }
            true
        }
        return elementList
    }

    private fun chooseInterface(project: Project, editor: Editor, file: PsiFile, struct: LuaTypeGuessable) {
        val model = InterfaceChooseContributeModel(project, arrayOf( LuaInterfaceContributor()))
        val provider = DefaultChooseByNameItemProvider(file)
        val popup = ChooseByNamePopup.createPopup(project, model, provider)
        val searchContext = SearchContext(project)
        val tyType = struct.guessType(searchContext) as TyStruct
        popup.invoke(object : ChooseByNamePopupComponent.Callback() {
            override fun elementChosen(element: Any?) {
                if (element is LuaTypeGuessable) {
                    val tyInterface = element.guessType(searchContext) as TyStruct
                    generateTemplate(project, editor, tyType, tyInterface)
                }
            }
        }, ModalityState.current(), false)
    }

    private fun generateTemplate(project: Project, editor: Editor, tyType: TyStruct, tyInterface: TyStruct) {
        val searchContext = SearchContext(project)
        val typeName = tyType.displayName

        ApplicationManager.getApplication().runWriteAction {
            var fieldOffset: Int = getFieldInsertPos(tyType, searchContext)
            var funcOffset : Int = getFuncInsertPos(tyType, searchContext)
            var commaFinish : Boolean = fieldFinishWithComma(tyType, searchContext)

            tyInterface.processMembers(searchContext) {_, m ->
                val name = m.name
                if (isAlreadyImplemented(tyType, name, searchContext)) {
                    return@processMembers
                }
                name?.let {
                    val member = tyInterface.findMember(name, searchContext)
                    val memberType = member?.guessType(searchContext)
                    if (memberType is ITyFunction) {
                        val funSignature = generateFun(typeName, name, memberType.mainSignature)
                        val moduleT = ModuleTemplate()
                        moduleT.addText("\n")
                        moduleT.addText(funSignature)
                        editor.caretModel.moveToOffset(funcOffset)
                        moduleT.startTemplate(project, editor)
                        funcOffset += funSignature.length + 1
                    }else if (member is LuaTableField){
                        val fieldExpr = generateField(member.fieldName, memberType)
                        val moduleT = ModuleTemplate()
                        var offset = 1
                        if (!commaFinish) {
                            moduleT.addText(",")
                            commaFinish = true
                            offset += 1
                        }
                        moduleT.addText("\n")
                        moduleT.addText(fieldExpr)
                        editor.caretModel.moveToOffset(fieldOffset)
                        moduleT.startTemplate(project, editor)
                        fieldOffset += fieldExpr.length + offset
                        funcOffset += fieldExpr.length + offset
                    }
                }
            }
        }
    }

    private fun generateFun(receiverName:String, funName:String, signature: IFunSignature):String {
        val parametersTuple = signature.params.filter { !it.isSelf }.joinToString(",") { Ty.toDocument(it.ty)}
        var returnsTuple = ""
        if (signature.returnTy !is TyVoid) {
            val returnTy = signature.returnTy
            if (returnTy is TyTuple) {
                returnsTuple = "(" + returnTy.list.joinToString(",") { Ty.toDocument(it)} + ")"
            }
        }

        return "func($receiverName)($parametersTuple)$returnsTuple.$funName = function${signature.paramSignature} \n" +
                "--TODO:Implement me \n" +
                "end"
    }

    private fun generateField(fieldName : String?, ity:ITy?):String {
        return "    $fieldName = ${Ty.toDocument(ity)},"
    }

    private fun getFieldInsertPos(tyStruct: TyStruct, searchContext: SearchContext):Int {
        var offset = 0
        var hasFound = false
        tyStruct.processMembers(searchContext) {_, classMember ->
            if (hasFound) {
                return@processMembers
            }
            if (classMember is LuaTableField) {
                val tableExpr = classMember.parent as LuaTableExpr
                val tableField = tableExpr.tableFieldList.last()
                val tableSep = tableExpr.tableFieldSepList.last()

                offset = max(tableField.textOffset + tableField.textLength, tableSep.textOffset + tableSep.textLength)
                hasFound = true
            }
        }
        return offset
    }

    private fun getFuncInsertPos(tyStruct: TyStruct, searchContext: SearchContext):Int {
        var offset = 0
        var hasFound = false
        tyStruct.processMembers(searchContext) {_, classMember ->
            if (hasFound) {
                return@processMembers
            }
            if (classMember is LuaTableField) {
                val structExpr = classMember.parent.parent
                offset = structExpr.textOffset + structExpr.textLength
                hasFound = true
            }
        }
        return offset
    }

    private fun fieldFinishWithComma(tyStruct: TyStruct, searchContext: SearchContext):Boolean {
        var commaFinish = false
        var hasFound = false
        tyStruct.processMembers(searchContext) {_, classMember ->
            if (hasFound) {
                return@processMembers
            }
            if (classMember is LuaTableField) {
                val tableExpr = classMember.parent as LuaTableExpr
                val tableField = tableExpr.tableFieldList.last()
                val tableSep = tableExpr.tableFieldSepList.last()

                commaFinish = tableField.textOffset + tableField.textLength < tableSep.textOffset + tableSep.textLength
                hasFound = true
            }
        }
        return commaFinish
    }

    private fun isAlreadyImplemented(tyStruct: TyStruct, name: String?, searchContext : SearchContext): Boolean {
        if (name == null) {
            return false
        }
        if (tyStruct.findMember(name, searchContext) != null) {
            return true
        }
        return false
    }

    private fun invoke(project: Project,  editor: Editor, struct: TyStruct) {

    }
}