package com.idemobile.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.HashSet;
import java.util.Set;

/**
 * Лёгкая подсветка синтаксиса без внешних зависимостей — порт highlight.js
 * из web-версии. Токенизирует код и возвращает Spannable с цветными спанами.
 * Палитра — VS Code Dark+ (те же цвета, что в css/main.css, блок .hl-*).
 *
 * Поддержка (через langFromPath): c-подобные (js/ts/java/kotlin/c/cpp/go/rust/
 * swift/php/scss/less/sql), python/ruby, shell, html/xml, css, json, markdown,
 * ini/yaml/toml. Неизвестные языки — просто текст (без падений).
 */
public class Highlight {

    // ---- палитра (VS Code Dark+) ----
    static final int COM    = Color.parseColor("#6A9955"); // комментарии
    static final int STR    = Color.parseColor("#CE9178"); // строки
    static final int NUM    = Color.parseColor("#B5CEA8"); // числа
    static final int KW     = Color.parseColor("#569CD6"); // ключевые слова
    static final int FN     = Color.parseColor("#DCDCAA"); // вызовы функций
    static final int TYPE   = Color.parseColor("#4EC9B0"); // типы/классы
    static final int CONST  = Color.parseColor("#569CD6"); // true/false/null, КОНСТ
    static final int PROP   = Color.parseColor("#9CDCFE"); // .свойство
    static final int KEY    = Color.parseColor("#9CDCFE"); // ключи json/css/ini
    static final int SEL    = Color.parseColor("#D7BA7D"); // css-селекторы
    static final int TAG    = Color.parseColor("#569CD6"); // html-теги
    static final int ATTR   = Color.parseColor("#9CDCFE"); // html-атрибуты
    static final int PUNC   = Color.parseColor("#D4D4D4"); // скобки/операторы
    static final int PREPROC= Color.parseColor("#C586C0"); // #include/#define
    static final int ANNOT  = Color.parseColor("#DCDCAA"); // @Override
    static final int MDH    = Color.parseColor("#569CD6"); // md заголовки
    static final int PLAIN  = Color.parseColor("#D4D4D4"); // обычный текст

    // ---- ключевые слова по языкам ----
    private static final String KW_JS =
        "break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await static get set of as from";
    private static final String KW_TS = KW_JS +
        " interface type enum namespace declare implements public private protected readonly abstract keyof infer is satisfies";
    private static final String KW_PY =
        "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield None True False self match case";
    private static final String KW_RUBY =
        "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require attr_accessor attr_reader attr_writer";
    private static final String KW_SMALI =
        "return return-void return-object return-wide move move-object move-result move-result-object move-result-wide invoke-virtual invoke-static invoke-direct invoke-super invoke-interface invoke-virtual/range invoke-static/range const const-string const-class const-wide const/4 const/16 const/high16 new-instance new-array iget iput iget-object iput-object sget sput sget-object sput-object goto if-eq if-ne if-lt if-ge if-gt if-le if-eqz if-nez if-ltz if-gez if-gtz if-lez check-cast instance-of throw nop aget aput array-length int-to-long long-to-int add-int sub-int mul-int div-int rem-int and-int or-int";
    private static final String KW_JAVA =
        "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var";
    private static final String KW_KT =
        "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by get set import abstract override open private protected public sealed suspend data companion init constructor lateinit vararg inline reified internal";
    private static final String KW_GO =
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false iota make new len cap append";
    private static final String KW_RUST =
        "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while";
    private static final String KW_C =
        "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while bool true false NULL";
    private static final String KW_CPP = KW_C +
        " catch class constexpr delete explicit friend namespace new nullptr operator private protected public template this throw try typename using virtual";
    private static final String KW_SWIFT =
        "associatedtype class deinit enum extension func import init let protocol struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch throw throws try false true nil self Self super";
    private static final String KW_PHP =
        "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty extends final finally fn for foreach function global goto if implements include instanceof interface isset list namespace new or print private protected public require return static switch throw trait try unset use var while xor yield true false null self this";
    private static final String KW_SHELL =
        "if then else elif fi for while until do done case esac in function select time echo cd export local return exit source alias set unset read test true false";
    private static final String KW_SQL =
        "select from where insert into values update set delete create table drop alter add column primary key foreign references index view join inner left right outer on group by order having limit offset union all as distinct and or not null is in like between count sum avg min max";

    private static final Set<String> BUILTIN_TYPES = toSet(
        "int long short byte char float double boolean bool void unsigned signed string String var let const auto size_t Object Integer Double Float Boolean Long List Map Set Array Int Unit Any Nothing");
    private static final Set<String> CONSTS = toSet(
        "true false null nil none None True False NULL nullptr undefined this self super");

    private static Set<String> toSet(String s) {
        Set<String> out = new HashSet<String>();
        for (String w : s.split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }
    private static Set<String> kwSet(String lang) {
        String s;
        if (lang.equals("typescript")) s = KW_TS;
        else if (lang.equals("python")) s = KW_PY;
        else if (lang.equals("ruby")) s = KW_RUBY;
        else if (lang.equals("java")) s = KW_JAVA;
        else if (lang.equals("kotlin")) s = KW_KT;
        else if (lang.equals("go")) s = KW_GO;
        else if (lang.equals("rust")) s = KW_RUST;
        else if (lang.equals("c")) s = KW_C;
        else if (lang.equals("cpp")) s = KW_CPP;
        else if (lang.equals("swift")) s = KW_SWIFT;
        else if (lang.equals("php")) s = KW_PHP;
        else if (lang.equals("shell")) s = KW_SHELL;
        else if (lang.equals("sql")) s = KW_SQL;
        else if (lang.equals("smali")) s = KW_SMALI;
        else s = KW_JS;
        return toSet(s);
    }

    /** Определить язык по пути файла (порт U.langFromPath). */
    public static String langFromPath(String path) {
        String e = ExplorerFs.extname(path);
        switch (e) {
            case "js": case "mjs": case "cjs": case "jsx": return "javascript";
            case "ts": case "tsx": return "typescript";
            case "html": case "htm": case "vue": case "svelte": return "html";
            case "xml": return "xml";
            case "css": return "css";
            case "scss": return "scss";
            case "less": return "less";
            case "json": return "json";
            case "md": case "markdown": return "markdown";
            case "yaml": case "yml": case "toml": case "ini": case "conf": return "ini";
            case "py": case "pyw": return "python";
            case "rb": return "ruby";
            case "go": return "go";
            case "rs": return "rust";
            case "c": case "h": case "m": return "c";
            case "cpp": case "cc": case "cxx": case "hpp": case "cs": case "mm": return "cpp";
            case "java": return "java";
            case "kt": case "kts": return "kotlin";
            case "swift": return "swift";
            case "php": return "php";
            case "sh": case "bash": case "zsh": return "shell";
            case "sql": return "sql";
            case "smali": return "smali";
            case "dockerfile": return "shell";
            case "makefile": return "shell";
            default: return "plaintext";
        }
    }

    // ---- построитель спанов ----
    private final SpannableStringBuilder sb = new SpannableStringBuilder();
    private void add(String text, int color) {
        int start = sb.length();
        sb.append(text);
        if (color != 0) sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private void addBold(String text, int color) {
        int start = sb.length();
        sb.append(text);
        if (color != 0) sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Главная точка входа: вернуть подсвеченный Spannable. */
    public static CharSequence highlight(String code, String lang) {
        if (code == null) return "";
        // Защитный лимит: подсветка (токенизация + тысячи span-объектов) огромного
        // текста вешает UI. Выше ~200 КБ возвращаем простой текст без подсветки —
        // редактор остаётся отзывчивым. Основную фильтрацию делает EditorFragment
        // (флаг highlightDisabled), это — страховка на случай прямых вызовов.
        if (code != null && code.length() > 200 * 1024) {
            return new SpannableStringBuilder(code);
        }
        Highlight h = new Highlight();
        try {
            h.run(code, lang == null ? "plaintext" : lang);
        } catch (Throwable e) {
            SpannableStringBuilder plain = new SpannableStringBuilder(code);
            return plain;
        }
        return h.sb;
    }
    public static CharSequence byPath(String code, String path) {
        return highlight(code, langFromPath(path));
    }

    private void run(String code, String lang) {
        if (lang.equals("json")) { json(code); return; }
        if (lang.equals("css") || lang.equals("scss") || lang.equals("less")) { css(code); return; }
        if (lang.equals("html") || lang.equals("xml")) { html(code); return; }
        if (lang.equals("markdown")) { markdown(code); return; }
        if (lang.equals("ini") || lang.equals("yaml") || lang.equals("toml")) { ini(code); return; }
        if (lang.equals("smali")) { smali(code); return; }
        if (lang.equals("plaintext") || lang.isEmpty()) { add(code, PLAIN); return; }
        boolean hash = lang.equals("python") || lang.equals("ruby") || lang.equals("shell");
        boolean block = !(lang.equals("python") || lang.equals("ruby") || lang.equals("shell"));
        boolean preproc = lang.equals("c") || lang.equals("cpp");
        boolean annotations = !(lang.equals("shell") || lang.equals("c") || lang.equals("cpp"));
        boolean templateStr = lang.equals("javascript") || lang.equals("typescript");
        generic(code, lang, hash, block, preproc, annotations, templateStr);
    }

    // ---- c-подобный токенайзер (+ python/shell через флаги) ----
    private static final String OP_CHARS = "+-*/%=<>!&|^~?:";
    private boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_' || c == '$'; }
    private boolean isIdentPart(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }
    private boolean isHex(char c) { return (c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F'); }

    private void generic(String code, String lang, boolean hashComment, boolean blockComment,
                         boolean preproc, boolean annotations, boolean templateStr) {
        Set<String> kw = kwSet(lang);
        int n = code.length(), i = 0;
        boolean atLineStart = true;
        while (i < n) {
            char c = code.charAt(i);
            String two = i + 1 < n ? code.substring(i, i + 2) : "";
            // Препроцессор C/C++
            if (preproc && atLineStart && c == '#') {
                int j = i; while (j < n && code.charAt(j) != '\n') j++;
                add(code.substring(i, j), PREPROC); i = j; atLineStart = false; continue;
            }
            // # комментарий
            if (hashComment && c == '#') {
                int j = i; while (j < n && code.charAt(j) != '\n') j++;
                add(code.substring(i, j), COM); i = j; continue;
            }
            // // комментарий
            if (two.equals("//")) {
                int j = i; while (j < n && code.charAt(j) != '\n') j++;
                add(code.substring(i, j), COM); i = j; continue;
            }
            // /* */
            if (blockComment && two.equals("/*")) {
                int j = code.indexOf("*/", i + 2); j = j < 0 ? n : j + 2;
                add(code.substring(i, j), COM); i = j; continue;
            }
            // строки
            if (c == '"' || c == '\'' || (templateStr && c == '`')) {
                int j = i + 1;
                while (j < n) {
                    char d = code.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == c) { j++; break; }
                    j++;
                }
                add(code.substring(i, Math.min(j, n)), STR); i = j; atLineStart = false; continue;
            }
            // аннотации @Word
            if (annotations && c == '@' && i + 1 < n && isIdentStart(code.charAt(i + 1))) {
                int j = i + 1; while (j < n && (isIdentPart(code.charAt(j)) || code.charAt(j) == '.')) j++;
                add(code.substring(i, j), ANNOT); i = j; atLineStart = false; continue;
            }
            // числа
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(code.charAt(i + 1)))) {
                int j = i;
                if (two.equals("0x") || two.equals("0X")) { j = i + 2; while (j < n && (isHex(code.charAt(j)) || code.charAt(j)=='_')) j++; }
                else if (two.equals("0b") || two.equals("0B")) { j = i + 2; while (j < n && (code.charAt(j)=='0'||code.charAt(j)=='1'||code.charAt(j)=='_')) j++; }
                else {
                    while (j < n && (Character.isDigit(code.charAt(j)) || code.charAt(j)=='.' || code.charAt(j)=='_')) j++;
                    if (j < n && (code.charAt(j)=='e'||code.charAt(j)=='E')) { j++; if (j<n&&(code.charAt(j)=='+'||code.charAt(j)=='-')) j++; while (j<n&&Character.isDigit(code.charAt(j))) j++; }
                }
                while (j < n && "uUlLfFdD".indexOf(code.charAt(j)) >= 0) j++;
                add(code.substring(i, j), NUM); i = j; atLineStart = false; continue;
            }
            // идентификаторы
            if (isIdentStart(c)) {
                int j = i; while (j < n && isIdentPart(code.charAt(j))) j++;
                String word = code.substring(i, j);
                int k = j; while (k < n && (code.charAt(k)==' '||code.charAt(k)=='\t')) k++;
                int p = i - 1; while (p >= 0 && (code.charAt(p)==' '||code.charAt(p)=='\t')) p--;
                boolean afterDot = p >= 0 && code.charAt(p) == '.';
                if (kw.contains(word)) add(word, KW);
                else if (BUILTIN_TYPES.contains(word)) add(word, TYPE);
                else if (CONSTS.contains(word)) add(word, CONST);
                else if (k < n && code.charAt(k) == '(') add(word, FN);
                else if (word.matches("^[A-Z][A-Za-z0-9_]*$") && !afterDot) add(word, TYPE);
                else if (word.matches("^[A-Z][A-Z0-9_]+$")) add(word, CONST);
                else if (afterDot) add(word, PROP);
                else add(word, PLAIN);
                i = j; atLineStart = false; continue;
            }
            // переносы/пробелы/пунктуация
            if (c == '\n') { sb.append('\n'); i++; atLineStart = true; continue; }
            if (c == ' ' || c == '\t') { sb.append(c); i++; continue; }
            if (c=='('||c==')'||c=='['||c==']'||c=='{'||c=='}') { add(String.valueOf(c), PUNC); i++; atLineStart=false; continue; }
            if (OP_CHARS.indexOf(c) >= 0) { add(String.valueOf(c), PUNC); i++; atLineStart=false; continue; }
            add(String.valueOf(c), PLAIN); i++; atLineStart = false;
        }
    }

    // ---- JSON ----
    private void json(String code) {
        int n = code.length(), i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n) { char d = code.charAt(j); if (d=='\\'){j+=2;continue;} if (d=='"'){j++;break;} j++; }
                int k = j; while (k < n && (code.charAt(k)==' '||code.charAt(k)=='\t')) k++;
                int color = (k < n && code.charAt(k) == ':') ? KEY : STR;
                add(code.substring(i, Math.min(j, n)), color); i = j; continue;
            }
            if ((c=='-'||Character.isDigit(c))) {
                int j = i + 1; while (j < n && "0123456789.eE+-".indexOf(code.charAt(j)) >= 0) j++;
                add(code.substring(i, j), NUM); i = j; continue;
            }
            if (Character.isLowerCase(c)) {
                int j = i; while (j < n && Character.isLowerCase(code.charAt(j))) j++;
                String w = code.substring(i, j);
                if (w.equals("true")||w.equals("false")||w.equals("null")) add(w, CONST); else add(w, PLAIN);
                i = j; continue;
            }
            if (c=='{'||c=='}'||c=='['||c==']') { add(String.valueOf(c), PUNC); i++; continue; }
            if (c==':'||c==',') { add(String.valueOf(c), PUNC); i++; continue; }
            sb.append(c); i++;
        }
    }

    // ---- CSS ----
    private void css(String code) {
        int n = code.length(), i = 0; boolean inBlock = false;
        while (i < n) {
            int start = i;
            char c = code.charAt(i);
            String two = i + 1 < n ? code.substring(i, i + 2) : "";
            if (two.equals("/*")) { int j = code.indexOf("*/", i+2); j = j<0?n:j+2; add(code.substring(i,j), COM); i=j; continue; }
            if (c=='"'||c=='\'') { int j=i+1; while(j<n&&code.charAt(j)!=c){ if(code.charAt(j)=='\\')j++; j++; } j++; add(code.substring(i,Math.min(j,n)),STR); i=j; continue; }
            if (c=='{') { inBlock=true; add("{",PUNC); i++; continue; }
            if (c=='}') { inBlock=false; add("}",PUNC); i++; continue; }
            if (c=='#' && i+1<n && isHex(code.charAt(i+1))) {
                int j=i+1; while(j<n&&isHex(code.charAt(j))) j++;
                if (j-i>=4) { add(code.substring(i,j),NUM); i=j; continue; }
            }
            if (inBlock && (Character.isLetter(c) || c=='-')) {
                int j=i; while(j<n && (Character.isLetter(code.charAt(j))||code.charAt(j)=='-')) j++;
                String word = code.substring(i,j);
                int k=j; while(k<n && Character.isWhitespace(code.charAt(k))) k++;
                if (k<n && code.charAt(k)==':') add(word,KEY);
                else if (j<n && code.charAt(j)=='(') add(word,FN);
                else add(word,CONST);
                i=j; if (i==start) { sb.append(code.charAt(i)); i++; } continue;
            }
            if (Character.isDigit(c) || (c=='-'&&i+1<n&&Character.isDigit(code.charAt(i+1)))) {
                int j=i+1; while(j<n && (Character.isDigit(code.charAt(j))||code.charAt(j)=='.'||Character.isLetter(code.charAt(j))||code.charAt(j)=='%')) j++;
                add(code.substring(i,j),NUM); i=j; continue;
            }
            if (c==':'||c==';'||c==',') { add(String.valueOf(c),PUNC); i++; continue; }
            if (c=='('||c==')') { add(String.valueOf(c),PUNC); i++; continue; }
            if (!inBlock && (c=='.'||c=='#'||c==':'||c=='@'||Character.isLetter(c)||c=='*'||c=='[')) {
                int j=i;
                while (j<n && "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.#:@_-[]=\"'()*>~+ ".indexOf(code.charAt(j))>=0 && code.charAt(j)!='{') j++;
                String selRaw = code.substring(i,j);
                String trimmed = selRaw.replaceAll("\\s+$","");
                add(trimmed, SEL);
                if (selRaw.length()>trimmed.length()) sb.append(selRaw.substring(trimmed.length()));
                i=j; if (i==start) { sb.append(code.charAt(i)); i++; } continue;
            }
            sb.append(c); i++;
            if (i<=start) i = start + 1;
        }
    }

    // ---- HTML/XML (упрощённо: теги, атрибуты, строки, комментарии) ----
    private void html(String code) {
        int n = code.length(), i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (i + 4 <= n && code.substring(i, i+4).equals("<!--")) {
                int j = code.indexOf("-->", i); j = j<0?n:j+3; add(code.substring(i,j), COM); i=j; continue;
            }
            if (c == '<') {
                int j = code.indexOf(">", i); if (j<0) j=n-1; j++;
                tag(code.substring(i, Math.min(j,n)));
                i = j; continue;
            }
            int j = code.indexOf("<", i); if (j<0) j=n;
            sb.append(code.substring(i, j)); i = j;
        }
    }
    private void tag(String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(</?)([a-zA-Z!][a-zA-Z0-9-]*)([\\s\\S]*?)(/?>)?$").matcher(tag);
        if (!m.matches()) { add(tag, PLAIN); return; }
        add(m.group(1), PUNC);
        addBold(m.group(2), TAG);
        String attrs = m.group(3);
        if (attrs != null) attrsHighlight(attrs);
        if (m.group(4) != null) add(m.group(4), PUNC);
    }
    private void attrsHighlight(String attrs) {
        int n = attrs.length(), i = 0;
        while (i < n) {
            char c = attrs.charAt(i);
            if (Character.isLetter(c) || c=='_' || c==':') {
                int j = i; while (j<n && (Character.isLetterOrDigit(attrs.charAt(j))||"_:.-".indexOf(attrs.charAt(j))>=0)) j++;
                add(attrs.substring(i,j), ATTR); i = j; continue;
            }
            if (c=='"'||c=='\'') {
                int j=i+1; while(j<n&&attrs.charAt(j)!=c) j++; j++;
                add(attrs.substring(i,Math.min(j,n)), STR); i=j; continue;
            }
            if (c=='=') { add("=", PUNC); i++; continue; }
            sb.append(c); i++;
        }
    }

    // ---- Markdown ----
    private void markdown(String code) {
        String[] lines = code.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.matches("^\\s{0,3}#{1,6}\\s.*")) addBold(line, MDH);
            else if (line.matches("^\\s{0,3}(```|~~~).*")) add(line, STR);
            else if (line.matches("^\\s{0,3}>.*")) add(line, COM);
            else {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^(\\s*)([-*+]|\\d+\\.)(\\s)(.*)$").matcher(line);
                if (m.matches()) {
                    sb.append(m.group(1));
                    addBold(m.group(2), MDH);
                    sb.append(m.group(3));
                    add(m.group(4), PLAIN);
                } else {
                    add(line, PLAIN);
                }
            }
            if (li < lines.length - 1) sb.append('\n');
        }
    }

    // ---- INI/YAML/TOML ----
    private void ini(String code) {
        String[] lines = code.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.matches("^\\s*[#;].*")) add(line, COM);
            else {
                java.util.regex.Matcher sec = java.util.regex.Pattern.compile("^(\\s*\\[)([^\\]]*)(\\].*)$").matcher(line);
                java.util.regex.Matcher kv = java.util.regex.Pattern.compile("^(\\s*[\\w.-]+\\s*)([:=])(.*)$").matcher(line);
                if (sec.matches()) {
                    sb.append(sec.group(1)); add(sec.group(2), SEL); sb.append(sec.group(3));
                } else if (kv.matches()) {
                    add(kv.group(1), KEY); sb.append(kv.group(2)); add(kv.group(3), STR);
                } else add(line, PLAIN);
            }
            if (li < lines.length - 1) sb.append('\n');
        }
    }

    // ---- Smali (байткод Dalvik) ----
    // Порт highlightSmali из web-версии: директивы (.method/.field), регистры
    // (v0/p1), типы (Ljava/lang/String;), строки, стрелка ->, метки (:goto_0),
    // числа, опкоды-ключевые слова. Комментарии начинаются с #.
    private void smali(String code) {
        Set<String> kw = kwSet("smali");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(\\.[a-z][a-z0-9-]*)"          + // 1: директива .method .field
            "|([vp]\\d+)"                   + // 2: регистр v0 p1
            "|(L[\\w$/]+;)"                 + // 3: тип Ljava/lang/String;
            "|(\"(?:[^\"\\\\]|\\\\.)*\")"   + // 4: строка
            "|(->)"                         + // 5: стрелка
            "|(:[a-zA-Z_][\\w]*)"           + // 6: метка :goto_0
            "|([{},()])"                    + // 7: пунктуация
            "|([a-zA-Z][\\w\\-/]*)"         + // 8: слово (опкод/идентификатор)
            "|(0x[0-9a-fA-F]+|-?\\d+)");      // 9: число
        String[] lines = code.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String com = null;
            int ci = line.indexOf('#');
            if (ci >= 0) { com = line.substring(ci); line = line.substring(0, ci); }
            java.util.regex.Matcher m = p.matcher(line);
            int last = 0;
            while (m.find()) {
                if (m.start() > last) add(line.substring(last, m.start()), PLAIN);
                if (m.group(1) != null) add(m.group(1), KW);
                else if (m.group(2) != null) add(m.group(2), PROP);
                else if (m.group(3) != null) add(m.group(3), TYPE);
                else if (m.group(4) != null) add(m.group(4), STR);
                else if (m.group(5) != null) add(m.group(5), PUNC);
                else if (m.group(6) != null) add(m.group(6), ANNOT);
                else if (m.group(7) != null) add(m.group(7), PUNC);
                else if (m.group(8) != null) { String w = m.group(8); add(w, kw.contains(w) ? FN : PLAIN); }
                else if (m.group(9) != null) add(m.group(9), NUM);
                last = m.end();
            }
            if (last < line.length()) add(line.substring(last), PLAIN);
            if (com != null) add(com, COM);
            if (li < lines.length - 1) sb.append('\n');
        }
    }
}
