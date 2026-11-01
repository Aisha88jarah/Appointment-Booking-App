package com.example.bookings.data

import android.content.Context
import com.example.bookings.R
import com.example.bookings.core.WorkWeek

/** Factory settings, with message templates in the device language. */
fun defaultSettings(c: Context) = AppSettings(
    businessName = "",
    address = "",
    stepMin = 30,
    bufferMin = 10,
    defaultDurationMin = 30,
    packTight = true,
    workWeek = WorkWeek.encode(WorkWeek.default()),
    confirmTpl = c.getString(R.string.tpl_confirm),
    declineTpl = c.getString(R.string.tpl_decline),
    rescheduleTpl = c.getString(R.string.tpl_reschedule),
    cancelTpl = c.getString(R.string.tpl_cancel),
)
