package com.supernova.launcher.ui.settings.groups

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.databinding.SheetNewGroupBinding
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.Sheets

// New tab / New folder, from the Drawer groups page: a name, tab or folder
// (opened on the one whose New row was tapped), and whether its apps stay in
// the main tab as well. Select apps makes the group and hands its stored name
// on; the page then opens it on its apps. A name missing or taken shows under
// the field and keeps the sheet up.
object NewGroupSheet {

    fun show(context: Context, viewModel: LauncherViewModel, type: DrawerGroupType, onCreated: (String) -> Unit) {
        val binding = SheetNewGroupBinding.inflate(LayoutInflater.from(context))
        val dialog = Sheets.open(context)
        dialog.setContentView(binding.root)

        fun picked(): DrawerGroupType {
            val folder = binding.newGroupType.checkedButtonId == R.id.newGroupFolder
            return if (folder) DrawerGroupType.FOLDER else DrawerGroupType.TAB
        }

        fun render() {
            val tab = picked() == DrawerGroupType.TAB
            binding.newGroupTitle.setText(if (tab) R.string.new_tab else R.string.new_folder_group)
            binding.newGroupHint.setText(if (tab) R.string.group_type_tab_hint else R.string.group_type_folder_hint)
        }

        fun create() {
            val name = binding.newGroupName.text?.toString().orEmpty().trim()
            val keep = binding.newGroupKeep.isChecked
            val created = if (name.isEmpty()) null else viewModel.createGroup(name, picked(), keep)
            if (created == null) {
                binding.newGroupNameLayout.error = context.getString(
                    if (name.isEmpty()) R.string.group_name_required else R.string.group_name_taken
                )
            } else {
                dialog.dismiss()
                onCreated(created)
            }
        }

        binding.newGroupType.check(if (type == DrawerGroupType.FOLDER) R.id.newGroupFolder else R.id.newGroupTab)
        binding.newGroupType.addOnButtonCheckedListener { _, _, checked -> if (checked) render() }
        render()
        binding.newGroupName.doAfterTextChanged { binding.newGroupNameLayout.error = null }
        binding.newGroupName.setOnEditorActionListener { _, actionId, _ ->
            val done = actionId == EditorInfo.IME_ACTION_DONE
            if (done) create()
            done
        }
        binding.newGroupCreate.setOnClickListener { create() }
        binding.newGroupCancel.setOnClickListener { dialog.dismiss() }

        // The name is the first thing to give: the keyboard comes up with
        // the sheet, which resizes to stay over it.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        binding.newGroupName.requestFocus()
        dialog.show()
    }
}
