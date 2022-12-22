/*
 * ************************************************************************
 *  RenameDialog.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

/**
 * **************************************************************************
 * PickTimeFragment.java
 * ****************************************************************************
 * Copyright © 2015 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * ***************************************************************************
 */
package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.textfield.TextInputEditText
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.util.parcelable
import org.videolan.vlc.R

const val RENAME_DIALOG_MEDIA = "RENAME_DIALOG_MEDIA"

class RenameDialog : VLCBottomSheetDialogFragment() {

    private lateinit var listener: (media: MediaLibraryItem, name: String) -> Unit
    private lateinit var renameButton: Button
    private lateinit var newNameInputtext: TextInputEditText
    private lateinit var media: MediaLibraryItem

    companion object {

        fun newInstance(media: MediaLibraryItem): RenameDialog {

            return RenameDialog().apply {
                arguments = bundleOf(RENAME_DIALOG_MEDIA to media)
            }
        }
    }

    fun setListener(listener:(media:MediaLibraryItem, name:String)->Unit) {
        this.listener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        media = arguments?.parcelable(RENAME_DIALOG_MEDIA) ?: return
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_rename, container)
        newNameInputtext = view.findViewById(R.id.new_name)
        renameButton = view.findViewById(R.id.rename_button)
        if (media.title.isNotEmpty()) newNameInputtext.setText(media.title)
        renameButton.setOnClickListener {
            performRename()
        }
        newNameInputtext.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performRename()
                true
            } else false
        }
        newNameInputtext.setOnKeyListener { _: View, keyCode: Int, keyEvent: KeyEvent ->
            if (keyCode == EditorInfo.IME_ACTION_DONE ||
                    keyCode == EditorInfo.IME_ACTION_GO ||
                    keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER) {
                performRename()
                true
            } else false
        }
        view.findViewById<TextView>(R.id.media_title).text = media.title
        return view
    }

    private fun performRename() {
        if (newNameInputtext.text.toString().isNotEmpty()) {
            listener.invoke(media, newNameInputtext.text.toString())
            dismiss()
        }
    }

    override fun getDefaultState(): Int {
        return STATE_EXPANDED
    }

    override fun initialFocusedView(): View = newNameInputtext

    override fun needToManageOrientation(): Boolean {
        return true
    }
}