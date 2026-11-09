package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ghaly.findme.R

/** A plain offline 4-function calculator. No mesh, no permissions, nothing
 *  to get wrong safety-wise — just arithmetic. Standard two-operand
 *  calculator behavior: pick a number, pick an operator, pick the next
 *  number, hit "=" (or chain straight into the next operator). */
class CalculatorFragment : Fragment(R.layout.fragment_calculator) {

    private var currentInput = "0"
    private var operand1: Double? = null
    private var pendingOperator: Char? = null
    private var startNewInput = true

    private lateinit var display: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        display = view.findViewById(R.id.calcDisplay)

        val digitIds = mapOf(
            R.id.btn0 to "0", R.id.btn1 to "1", R.id.btn2 to "2", R.id.btn3 to "3",
            R.id.btn4 to "4", R.id.btn5 to "5", R.id.btn6 to "6", R.id.btn7 to "7",
            R.id.btn8 to "8", R.id.btn9 to "9"
        )
        for ((id, digit) in digitIds) {
            view.findViewById<Button>(id).setOnClickListener { onDigit(digit) }
        }

        view.findViewById<Button>(R.id.btnDot).setOnClickListener { onDot() }
        view.findViewById<Button>(R.id.btnClear).setOnClickListener { onClear() }
        view.findViewById<Button>(R.id.btnBackspace).setOnClickListener { onBackspace() }
        view.findViewById<Button>(R.id.btnAdd).setOnClickListener { onOperator('+') }
        view.findViewById<Button>(R.id.btnSub).setOnClickListener { onOperator('-') }
        view.findViewById<Button>(R.id.btnMul).setOnClickListener { onOperator('×') }
        view.findViewById<Button>(R.id.btnDiv).setOnClickListener { onOperator('÷') }
        view.findViewById<Button>(R.id.btnEquals).setOnClickListener { onEquals() }

        updateDisplay()
    }

    private fun onDigit(digit: String) {
        currentInput = if (startNewInput || currentInput == "0") {
            startNewInput = false
            digit
        } else {
            currentInput + digit
        }
        updateDisplay()
    }

    private fun onDot() {
        if (startNewInput) {
            currentInput = "0."
            startNewInput = false
        } else if (!currentInput.contains(".")) {
            currentInput += "."
        }
        updateDisplay()
    }

    private fun onClear() {
        currentInput = "0"
        operand1 = null
        pendingOperator = null
        startNewInput = true
        updateDisplay()
    }

    private fun onBackspace() {
        if (startNewInput) return
        currentInput = if (currentInput.length > 1) currentInput.dropLast(1) else "0"
        if (currentInput == "0") startNewInput = true
        updateDisplay()
    }

    private fun onOperator(op: Char) {
        if (operand1 != null && pendingOperator != null && !startNewInput) {
            compute()
        } else {
            operand1 = currentInput.toDoubleOrNull() ?: 0.0
        }
        pendingOperator = op
        startNewInput = true
        updateDisplay()
    }

    private fun onEquals() {
        if (operand1 == null || pendingOperator == null) return
        compute()
        pendingOperator = null
        startNewInput = true
        updateDisplay()
    }

    /** Runs the pending operation against [currentInput] as the second
     *  operand, leaving the result as the new [operand1] so "=" can be
     *  tapped again to repeat it, same as any plain calculator. */
    private fun compute() {
        val op2 = currentInput.toDoubleOrNull() ?: return
        val a = operand1 ?: return
        val result = when (pendingOperator) {
            '+' -> a + op2
            '-' -> a - op2
            '×' -> a * op2
            '÷' -> if (op2 == 0.0) null else a / op2
            else -> null
        }
        if (result == null || result.isNaN() || result.isInfinite()) {
            currentInput = getString(R.string.calc_error)
            operand1 = null
            return
        }
        currentInput = formatResult(result)
        operand1 = result
    }

    /** Whole numbers print without a trailing ".0"; fractional results are
     *  rounded to a sane number of digits so float noise like
     *  0.1 + 0.2 = 0.30000000000000004 never shows up. */
    private fun formatResult(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return "%.8f".format(value).trimEnd('0').trimEnd('.')
    }

    private fun updateDisplay() {
        display.text = currentInput
    }
}
