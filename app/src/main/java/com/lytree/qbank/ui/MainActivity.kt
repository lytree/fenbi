package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ActivityMainBinding
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
                R.id.nav_recite -> WordListFragment()
                R.id.nav_records -> RecordListFragment()
                else -> PaperListFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
            true
        }
        // 政治背诵、常识背诵、设置都是不占底部导航的二级页：停在二级页时再点所属 Tab 应回到它的首页
        binding.bottomNav.setOnItemReselectedListener { item ->
            val current = supportFragmentManager.findFragmentById(R.id.container)
            val home = when (item.itemId) {
                R.id.nav_recite -> if (current is WordListFragment) null else WordListFragment()
                R.id.nav_records -> if (current is RecordListFragment) null else RecordListFragment()
                else -> null
            } ?: return@setOnItemReselectedListener
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, home)
                .commit()
        }
        // 首次启动直接放首页。不能靠 selectedItemId 触发上面的监听器：
        // BottomNavigationView 载入菜单时第一项（nav_papers）就已经是选中态，
        // 再 setSelectedItemId 同一个 id 只会走「重选」分支，不会回调 onItemSelected，
        // 结果容器一直是空的——装完首次打开表现为首页空白，切到词库再切回来才出内容
        if (savedInstanceState == null && supportFragmentManager.findFragmentById(R.id.container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, PaperListFragment())
                .commit()
        }

        // 闹钟在重装 / 强制停止后会被系统清掉，每次启动重新挂一次（没开自动备份就只是取消）
        com.lytree.qbank.data.backup.AutoBackupScheduler.sync(this)
    }

    override fun onResume() {
        super.onResume()
        // 让当前 fragment 刷新数据（view 还没建好的跳过，避免 refresh 里拿空 binding）
        supportFragmentManager.fragments.forEach { f ->
            if (f is Refreshable && f.view != null) f.refresh()
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
