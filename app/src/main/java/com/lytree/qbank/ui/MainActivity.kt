package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ActivityMainBinding
import com.lytree.qbank.ui.bank.BankListFragment
import com.lytree.qbank.ui.paper.PaperListFragment
import com.lytree.qbank.ui.record.RecordListFragment
import com.lytree.qbank.ui.word.WordListFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 15 强制 edge-to-edge：底部导航需要自己让出系统导航栏高度
        binding.bottomNav.applyNavBarBottomPadding()

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_words -> WordListFragment()
                R.id.nav_papers -> PaperListFragment()
                R.id.nav_records -> RecordListFragment()
                else -> BankListFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
            true
        }
        // 成语汇总与设置都是不占底部导航的二级页：停在二级页时再点所属 Tab 应回到它的首页
        binding.bottomNav.setOnItemReselectedListener { item ->
            val current = supportFragmentManager.findFragmentById(R.id.container)
            val home = when (item.itemId) {
                R.id.nav_words -> if (current is WordListFragment) null else WordListFragment()
                R.id.nav_records -> if (current is RecordListFragment) null else RecordListFragment()
                else -> null
            } ?: return@setOnItemReselectedListener
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, home)
                .commit()
        }
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_banks
        }
    }

    override fun onResume() {
        super.onResume()
        // 让当前 fragment 刷新数据
        supportFragmentManager.fragments.forEach { f ->
            if (f is Refreshable) f.refresh()
        }
    }

    fun openImport() {
        startActivity(Intent(this, ImportActivity::class.java))
    }
}

/** 可刷新的 fragment 接口 */
interface Refreshable {
    fun refresh()
}
