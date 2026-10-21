package com.lytree.qbank.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.databinding.FragmentSettingsBinding
import com.lytree.qbank.ui.Refreshable

/**
 * 判题规则设置。不同机构口径不同，这里让用户自己定。
 */
class SettingsFragment : Fragment(), Refreshable {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: QBankDb

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        val p = db.getPolicy()
        binding.swMulti.isChecked = p.multiPartial
        binding.swUncertain.isChecked = p.uncertainPartial
        binding.swFill.isChecked = p.fillPartial
        binding.swSubjective.isChecked = p.subjectivePartial
        binding.swJudgeLenient.isChecked = p.judgeLenient

        val save = {
            db.setPolicy(
                com.lytree.qbank.engine.Policy(
                    multiPartial = binding.swMulti.isChecked,
                    uncertainPartial = binding.swUncertain.isChecked,
                    uncertainPartialRatio = 0.5,
                    fillPartial = binding.swFill.isChecked,
                    subjectivePartial = binding.swSubjective.isChecked,
                    judgeLenient = binding.swJudgeLenient.isChecked
                )
            )
        }
        listOf(
            binding.swMulti, binding.swUncertain, binding.swFill,
            binding.swSubjective, binding.swJudgeLenient
        ).forEach { it.setOnCheckedChangeListener { _, _ -> save() } }
    }

    override fun refresh() = Unit

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
