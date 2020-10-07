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

package com.tang.intellij.lua;

/**
 * Constants
 * Created by TangZX on 2017/1/19.
 */
public class Constants {
    public static final String WORD_SELF = "self";
    public static final String WORD_UNDERLINE = "_";
    public static final String WORD_G = "_G";
    public static final String WORD_PAIRS = "pairs";
    public static final String WORD_IPAIRS = "ipairs";
    public static final String WORD_ANY = "any";
    public static final String WORD_VOID = "void";
    public static final String WORD_MODULE = "module";
    public static final String WORD_NIL = "nil";
    public static final String WORD_STRING = "string";
    public static final String WORD_BOOLEAN = "boolean";
    public static final String WORD_NUMBER = "number";
    public static final String WORD_TABLE = "table";
    public static final String WORD_FUNCTION = "function";
    public static final String WORD_REQUIRE = "require";
    public static final String WORD_IMPORT = "import";
    public static final String WORD_STRUCT = "struct";
    public static final String WORD_INTERFACE = "interface";
    public static final String WORD_FUNCDEF = "func";
    public static final String WORD_FUNC_DECLARE = "fn";
    public static final String WORD_MAP = "map";
    public static final String WORD_LIST = "list";
    public static final String WORD_NULLABLE = "nullable";
    public static final String WORD_ENUM = "enum";
    public static final String WORD_EXPORT = "export";

    public static final int ST_NONE = 0;
    public static final int ST_IMPLEMENT = 1;
    public static final int ST_INTERFACE = 2;
    public static final int ST_ENUM = 3;

    public static Boolean IsStructOrInterfaceDefWord(String word) {
        return word.equals(WORD_STRUCT) || word.equals(WORD_INTERFACE);
    }

    public static Boolean IsSpecTypeDefWord(String word) {
        return word.equals(WORD_STRUCT) || word.equals(WORD_INTERFACE) || word.equals(WORD_ENUM);
    }

    public static Boolean IsImportPakWord(String word) {
        return word.equals(WORD_REQUIRE) || word.equals(WORD_IMPORT);
    }

    public static Boolean IsPackageWord(String word) {
        return word.equals(WORD_MODULE) || word.equals(WORD_EXPORT);
    }
}
