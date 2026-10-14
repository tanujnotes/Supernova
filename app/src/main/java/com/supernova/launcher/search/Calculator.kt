package com.supernova.launcher.search

import kotlin.math.abs
import kotlin.math.pow

// On-device evaluator behind the search microresults: arithmetic expressions
// ("5*8+2") and unit conversions ("12 km to mi", "100 f in c"). Anything that
// doesn't parse returns null so the search surface shows nothing rather than
// a junk row. Pure Kotlin, no Android types, fully unit-testable.
object Calculator {

    data class Result(val display: String)

    fun evaluate(raw: String): Result? {
        val text = normalizeNumbers(raw.trim().lowercase()) ?: return null
        if (text.isEmpty()) return null
        convertUnits(text)?.let { return it }
        return evaluateExpression(text)
    }

    // ------------------------------------------------------------- numbers

    private val numberRun = Regex("""\d[\d.,]*""")

    // Every number rewritten with a plain '.' decimal point and no grouping,
    // or null for one that can't be read. Commas used to be dropped as
    // thousands separators, so "1,5+1" - one and a half, as much of the
    // world writes it - came out as 16.
    private fun normalizeNumbers(text: String): String? {
        if (',' !in text) return text
        var invalid = false
        val normalized = numberRun.replace(text) { match ->
            normalizeRun(match.value) ?: run {
                invalid = true
                match.value
            }
        }
        return if (invalid) null else normalized
    }

    // A comma groups digits in "1,000" and "12,345,678", and in India's
    // "1,00,000" (pairs after the first thousand); with a point as well it
    // groups in "1,234.5". It is the decimal point in "1,5" or "3,14" - one
    // comma not followed by exactly three digits - and in "1.234,5", where
    // it comes after the points.
    private fun normalizeRun(run: String): String? {
        if (',' !in run) return run
        val lastComma = run.lastIndexOf(',')
        val lastPoint = run.lastIndexOf('.')
        if (lastPoint >= 0) {
            return if (lastPoint > lastComma) run.replace(",", "") else run.replace(".", "").replace(',', '.')
        }
        val groups = run.split(',')
        if (groups.size == 2 && groups[1].length != 3) {
            return if (groups[1].isEmpty()) null else "${groups[0]}.${groups[1]}"
        }
        val tail = groups.drop(1)
        if (tail.last().length != 3 || tail.any { it.length !in 2..3 }) return null
        return groups.joinToString("")
    }

    // ---------------------------------------------------------------- units

    private class Unit(val symbol: String, val toBase: Double, val category: Int)

    private const val LENGTH = 0
    private const val MASS = 1
    private const val DATA = 2
    private const val TIME = 3
    private const val TEMP = 4

    private val units: Map<String, Unit> = buildMap {
        fun put(unit: Unit, vararg names: String) = names.forEach { this[it] = unit }
        put(Unit("mm", 0.001, LENGTH), "mm", "millimeter", "millimeters")
        put(Unit("cm", 0.01, LENGTH), "cm", "centimeter", "centimeters")
        put(Unit("m", 1.0, LENGTH), "m", "meter", "meters", "metre", "metres")
        put(Unit("km", 1000.0, LENGTH), "km", "kilometer", "kilometers")
        put(Unit("in", 0.0254, LENGTH), "in", "inch", "inches")
        put(Unit("ft", 0.3048, LENGTH), "ft", "foot", "feet")
        put(Unit("yd", 0.9144, LENGTH), "yd", "yard", "yards")
        put(Unit("mi", 1609.344, LENGTH), "mi", "mile", "miles")
        put(Unit("mg", 1e-6, MASS), "mg")
        put(Unit("g", 0.001, MASS), "g", "gram", "grams")
        put(Unit("kg", 1.0, MASS), "kg", "kilogram", "kilograms")
        put(Unit("oz", 0.0283495231, MASS), "oz", "ounce", "ounces")
        put(Unit("lb", 0.45359237, MASS), "lb", "lbs", "pound", "pounds")
        put(Unit("t", 1000.0, MASS), "t", "ton", "tons", "tonne", "tonnes")
        put(Unit("B", 1.0, DATA), "b", "byte", "bytes")
        put(Unit("KB", 1024.0, DATA), "kb")
        put(Unit("MB", 1024.0 * 1024, DATA), "mb")
        put(Unit("GB", 1024.0 * 1024 * 1024, DATA), "gb")
        put(Unit("TB", 1024.0 * 1024 * 1024 * 1024, DATA), "tb")
        put(Unit("ms", 0.001, TIME), "ms")
        put(Unit("s", 1.0, TIME), "s", "sec", "secs", "second", "seconds")
        put(Unit("min", 60.0, TIME), "min", "mins", "minute", "minutes")
        put(Unit("h", 3600.0, TIME), "h", "hr", "hrs", "hour", "hours")
        put(Unit("d", 86400.0, TIME), "d", "day", "days")
        put(Unit("wk", 604800.0, TIME), "wk", "week", "weeks")
        put(Unit("°C", 1.0, TEMP), "c", "celsius", "°c")
        put(Unit("°F", 1.0, TEMP), "f", "fahrenheit", "°f")
        put(Unit("K", 1.0, TEMP), "k", "kelvin")
    }

    // "<number?> <unit> to|in|as <unit>"
    private val conversionPattern =
        Regex("""^(-?\d+(?:\.\d+)?)?\s*([a-z°]+)\s+(?:to|in|as)\s+([a-z°]+)$""")

    private fun convertUnits(text: String): Result? {
        val match = conversionPattern.matchEntire(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: 1.0
        val from = units[match.groupValues[2]] ?: return null
        val to = units[match.groupValues[3]] ?: return null
        if (from.category != to.category) return null
        val converted = if (from.category == TEMP) {
            convertTemperature(amount, from.symbol, to.symbol)
        } else {
            amount * from.toBase / to.toBase
        }
        if (!converted.isFinite()) return null
        return Result("${formatNumber(amount)} ${from.symbol} = ${formatNumber(converted)} ${to.symbol}")
    }

    private fun convertTemperature(value: Double, from: String, to: String): Double {
        val kelvin = when (from) {
            "°C" -> value + 273.15
            "°F" -> (value - 32) * 5 / 9 + 273.15
            else -> value
        }
        return when (to) {
            "°C" -> kelvin - 273.15
            "°F" -> (kelvin - 273.15) * 9 / 5 + 32
            else -> kelvin
        }
    }

    // ----------------------------------------------------------- expression

    private sealed class Token {
        class Num(val value: Double) : Token()
        class Op(val symbol: Char) : Token()
        object Open : Token()
        object Close : Token()
    }

    private fun evaluateExpression(text: String): Result? {
        val tokens = tokenize(text) ?: return null
        // A bare number is a query, not a calculation.
        if (tokens.none { it is Token.Op }) return null
        val value = evalRpn(toRpn(tokens) ?: return null) ?: return null
        if (!value.isFinite()) return null
        return Result("= ${formatNumber(value)}")
    }

    private fun tokenize(text: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    val value = text.substring(start, i).toDoubleOrNull() ?: return null
                    tokens.add(Token.Num(value))
                }
                c == '(' -> { tokens.add(Token.Open); i++ }
                c == ')' -> { tokens.add(Token.Close); i++ }
                c in "+*/%^" || c == '×' || c == '÷' -> {
                    tokens.add(Token.Op(when (c) { '×' -> '*'; '÷' -> '/'; else -> c }))
                    i++
                }
                c == '-' -> {
                    // Unary minus folds into the following number.
                    val prev = tokens.lastOrNull()
                    val unary = prev == null || prev is Token.Op || prev is Token.Open
                    if (unary) {
                        i++
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                        if (start == i) return null
                        val value = text.substring(start, i).toDoubleOrNull() ?: return null
                        tokens.add(Token.Num(-value))
                    } else {
                        tokens.add(Token.Op('-'))
                        i++
                    }
                }
                else -> return null
            }
        }
        return tokens.takeIf { it.isNotEmpty() }
    }

    private fun precedence(op: Char): Int = when (op) {
        '^' -> 3
        '*', '/', '%' -> 2
        else -> 1
    }

    private fun toRpn(tokens: List<Token>): List<Token>? {
        val output = mutableListOf<Token>()
        val stack = ArrayDeque<Token>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> output.add(token)
                is Token.Op -> {
                    while (stack.isNotEmpty()) {
                        val top = stack.last() as? Token.Op ?: break
                        val leftAssoc = token.symbol != '^'
                        val outranked = precedence(top.symbol) > precedence(token.symbol) ||
                            (leftAssoc && precedence(top.symbol) == precedence(token.symbol))
                        if (!outranked) break
                        output.add(stack.removeLast())
                    }
                    stack.addLast(token)
                }
                is Token.Open -> stack.addLast(token)
                is Token.Close -> {
                    while (stack.isNotEmpty() && stack.last() !is Token.Open) {
                        output.add(stack.removeLast())
                    }
                    if (stack.isEmpty()) return null
                    stack.removeLast()
                }
            }
        }
        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            if (top is Token.Open) return null
            output.add(top)
        }
        return output
    }

    private fun evalRpn(rpn: List<Token>): Double? {
        val stack = ArrayDeque<Double>()
        for (token in rpn) {
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
                    if (stack.size < 2) return null
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(
                        when (token.symbol) {
                            '+' -> a + b
                            '-' -> a - b
                            '*' -> a * b
                            '/' -> a / b
                            '%' -> a % b
                            '^' -> a.pow(b)
                            else -> return null
                        }
                    )
                }
                else -> return null
            }
        }
        return stack.singleOrNull()
    }

    private fun formatNumber(value: Double): String {
        if (value == 0.0) return "0"
        if (abs(value) >= 1e15 || abs(value) < 1e-9) return value.toString()
        val whole = value.toLong()
        if (whole.toDouble() == value) return whole.toString()
        // Up to 6 decimals, trailing zeros trimmed.
        return String.format(java.util.Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')
    }
}
