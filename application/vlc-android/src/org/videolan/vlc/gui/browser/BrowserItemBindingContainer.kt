/*
 * ************************************************************************
 *  BrowserItemBindingContainer.kt
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

package org.videolan.vlc.gui.browser

import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.databinding.ViewDataBinding
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.vlc.databinding.BrowserItemBinding
import org.videolan.vlc.databinding.CardBrowserItemBinding
import org.videolan.vlc.gui.helpers.ThreeStatesCheckbox

class BrowserItemBindingContainer(val binding: ViewDataBinding) {
    fun setCheckEnabled(enabled: Boolean) {
        when (binding) {
            is CardBrowserItemBinding -> binding.checkEnabled = enabled
            is BrowserItemBinding -> binding.checkEnabled = enabled
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    fun setCover(value: BitmapDrawable) {
        when (binding) {
            is CardBrowserItemBinding -> {
                binding.cover = value
            }
            is BrowserItemBinding -> {
                binding.cover = value
            }
        }
    }

    fun setProtocol(protocol: String?) {
        when (binding) {
            is CardBrowserItemBinding -> binding.protocol = protocol
            is BrowserItemBinding -> binding.protocol = protocol
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    fun setFileName(filename: String?) {
        when (binding) {
            is CardBrowserItemBinding -> binding.filename = filename
            is BrowserItemBinding -> binding.filename = filename
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    fun setHasContextMenu(hasContextMenu: Boolean) {
        when (binding) {
            is CardBrowserItemBinding -> binding.hasContextMenu = hasContextMenu
            is BrowserItemBinding -> binding.hasContextMenu = hasContextMenu
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    fun setIsBanned(banned: Boolean) {
        when (binding) {
            is BrowserItemBinding -> binding.isBanned = banned
        }
    }

    fun setIsBannedByParent(banned: Boolean) {
        when (binding) {
            is BrowserItemBinding -> binding.isBannedParent = banned
        }
    }

    fun setItem(item: MediaLibraryItem) {
        when (binding) {
            is CardBrowserItemBinding -> binding.item = item
            is BrowserItemBinding -> binding.item = item
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    fun setIsFavorite(favorite:Boolean) {
        when (binding) {
            is CardBrowserItemBinding -> binding.favorite = favorite
            is BrowserItemBinding -> binding.favorite = favorite
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    fun setIsTv(isTv:Boolean) {
        when (binding) {
            is BrowserItemBinding -> binding.isTv = isTv
        }
    }

    fun setHolder(holder: BaseBrowserAdapter.ViewHolder<ViewDataBinding>) {
        when (binding) {
            is CardBrowserItemBinding -> binding.holder = holder
            is BrowserItemBinding -> binding.holder = holder
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }

    var title: TextView
    var itemIcon: ImageView
    var browserCheckbox: ThreeStatesCheckbox
    var banIcon: ImageView
    var text: TextView
    var container: View
    var moreIcon: ImageView

    init {
        if (binding !is CardBrowserItemBinding && binding !is BrowserItemBinding) throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        when (binding) {
            is CardBrowserItemBinding -> {
                text = binding.text
                title = binding.title
                itemIcon = binding.itemIcon
                browserCheckbox = binding.browserCheckbox
                moreIcon = binding.itemMore
                container = binding.browserContainer
                banIcon = binding.itemBan
            }
            is BrowserItemBinding -> {
                text = binding.text
                title = binding.title
                itemIcon = binding.itemIcon
                browserCheckbox = binding.browserCheckbox
                moreIcon = binding.itemMore
                container = binding.browserContainer
                banIcon = binding.itemBan
            }
            else -> throw IllegalStateException("Binding should be either a CardBrowserItemBinding or BrowserItemBinding")
        }
    }
}