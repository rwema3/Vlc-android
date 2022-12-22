/*
 * *************************************************************************
 *  FilePickerAdapter.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.browser

import androidx.databinding.ViewDataBinding
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem

class FilePickerAdapter internal constructor(browserContainer: BrowserContainer<MediaLibraryItem>) : BaseBrowserAdapter(browserContainer) {

    override fun onBindViewHolder(holder: ViewHolder<ViewDataBinding>, position: Int) {
        val h = holder as MediaViewHolder
        val media = getItem(position) as MediaWrapper
        h.bindingContainer.setItem(media)
        h.bindingContainer.setHasContextMenu(false)
        h.bindingContainer.setProtocol(null)
        h.bindingContainer.setCover(getIcon(media, false))
    }
}
