/**
 * Copyright 2012-2015 Kevin Hausmann
 *
 * This file is part of Podcatcher Deluxe.
 *
 * Podcatcher Deluxe is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * Podcatcher Deluxe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Podcatcher Deluxe. If not, see <http://www.gnu.org/licenses/>.
 */

package com.podcatcher.deluxe;

import android.content.Context;

/**
 * Show fullscreen video activity. Uses the VLC player.
 */
public class VlcPlayerFullscreenVideoActivity extends BaseActivity {

    public static boolean isAvailable(Context context) {
        /*try {
            final ComponentName componentName = new ComponentName("org.videolan.vlc.betav7neon",
                "org.videolan.vlc.betav7neon.gui.video.VideoPlayerActivity");
            context.getPackageManager().getActivityInfo(componentName, 0);

            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }*/

        return false;
    }
}
