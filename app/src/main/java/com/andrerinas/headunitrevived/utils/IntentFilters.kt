package com.andrerinas.headunitrevived.utils

import android.content.IntentFilter
import com.andrerinas.headunitrevived.contract.KeyIntent

object IntentFilters {
    val keyEvent = IntentFilter(KeyIntent.action)
}