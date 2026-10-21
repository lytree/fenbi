package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.databinding.ActivityComposeBinding

/**
 * 组卷：选题库 → 设题量/时长/题型/难度/知识点/地区 → 随机抽题生成试卷。
 */
class ComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposeBinding
    private lateinit var db: QBankDb
    private var banks = emptyList<com.lytree.qbank.data.model.Bank>()
    private var bankId = 0L

    private val difficultyLabels = arrayOf("全部难度", "容易", "中等", "困难")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.inputCount.setText("20")
        binding.inputDuration.setText("0")

        binding.spinnerDifficulty.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, difficultyLabels)

        loadBanks()
        binding.btnGenerate.setOnClickListener { generate() }
    }

    private fun loadBanks() {
        banks = db.listBanks()
        if (banks.isEmpty()) {
            Toast.makeText(this, "请先导入题库", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.spinnerBank.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            banks.map { "${it.name}（${it.questionCount}题）" }
        )
        binding.spinnerBank.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                bankId = banks[pos].id
                binding.tvBankInfo.text = "共 ${banks[pos].questionCount} 道题"
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    private fun generate() {
        if (bankId == 0L) { Toast.makeText(this, "请选择题库", Toast.LENGTH_SHORT).show(); return }
        val count = binding.inputCount.text?.toString()?.toIntOrNull() ?: 10
        if (count <= 0) { Toast.makeText(this, "题量需大于 0", Toast.LENGTH_SHORT).show(); return }
        val duration = binding.inputDuration.text?.toString()?.toIntOrNull() ?: 0

        val types = buildSet {
            if (binding.cbSingle.isChecked) add(QuestionType.SINGLE)
            if (binding.cbMulti.isChecked) add(QuestionType.MULTI)
            if (binding.cbUncertain.isChecked) add(QuestionType.UNCERTAIN)
            if (binding.cbJudge.isChecked) add(QuestionType.TRUE_FALSE)
        }
        if (types.isEmpty()) {
            Toast.makeText(this, "请至少选择一种题型", Toast.LENGTH_SHORT).show(); return
        }

        val diffPos = binding.spinnerDifficulty.selectedItemPosition
        val difficulty = when (diffPos) { 1 -> 1; 2 -> 2; 3 -> 3; else -> null }
        val knowledge = binding.inputKnowledge.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val region = binding.inputRegion.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val picked = db.pickQuestions(bankId, types, knowledge, difficulty, region, count)
        if (picked.isEmpty()) {
            Toast.makeText(this, "没有符合条件的题目", Toast.LENGTH_SHORT).show(); return
        }

        val name = binding.inputName.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "练习 ${System.currentTimeMillis() % 100000}"
        val pid = db.insertPaper(name, bankId, duration, "随机组卷", picked)
        startActivity(
            Intent(this, ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, pid)
            }
        )
        finish()
    }
}
