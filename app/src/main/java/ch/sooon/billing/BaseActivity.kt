/** ========================================================================
Creation: 25/02/21
Revision: $
Creator: Thierry Raeber (thierry@sooon.ch)
Notice: (C) Copyright 2021 by Sooon SA. All Rights Reserved.
============================================================================ */

package ch.sooon.billing

import android.app.Activity
import android.os.Bundle
import ch.sooon.billing.MyWatchFace.Companion.billingManager
import ch.sooon.billing.databinding.BaseActivityBinding

class BaseActivity : Activity() {
	private val TAG = this.javaClass.simpleName

	private lateinit var binding: BaseActivityBinding

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		binding = BaseActivityBinding.inflate(layoutInflater)
		val view = binding.root
		setContentView(view)
       	billingManager?.launchPurchaseFlow(this)
	}

}