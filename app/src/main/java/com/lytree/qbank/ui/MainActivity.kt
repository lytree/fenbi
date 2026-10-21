package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ActivityMainBinding
import com.lytree.qbank.ui.bank.BankListFragment
import com.lytree.qbank.ui.paper.PaperListFragment
import com.lytree.qbank.ui.record.RecordListFragment
import com.lytree.qbank.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_papers -> PaperListFragment()
                R.id.nav_records -> RecordListFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> BankListFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
            true
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
