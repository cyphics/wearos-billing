/** ========================================================================
Creation: 30/12/20
Revision: $
Creator: Thierry Raeber (thierry@sooon.ch)
Notice: (C) Copyright 2020 by Sooon SA. All Rights Reserved.
============================================================================ */

package ch.sooon.billing

import android.util.Log
import androidx.lifecycle.MutableLiveData

/** This class serves as a simple notifier of data change. Anyone can subscribe to it
 * and be notified when a new data is available
 *
  */

class Bell(private val callerTag: String) {
    private val TAG = this.javaClass.simpleName
    private val liveBoolean = MutableLiveData<Boolean>()

    fun ring() {
        Log.d(TAG, "Bell rang from $callerTag")
        liveBoolean.postValue(liveBoolean.value ?: false)
    }

    fun listen(function: () -> Unit, tag: String = "") {
        liveBoolean.observeForever {
			if (tag.isNotEmpty())
			    Log.d(callerTag, "$tag has been notified of update")
            function()
        }
    }
}