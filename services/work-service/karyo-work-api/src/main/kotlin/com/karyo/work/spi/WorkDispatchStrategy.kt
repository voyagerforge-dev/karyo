package com.karyo.work.spi

import com.karyo.work.dto.WorkItem

/**
 * Orders the merged candidate pool for dispatch.
 *
 * **Selection is BY NAME, not by priority (St6, stocktaking-block sprint).**
 * [com.karyo.work.service.WorkDispatchService] picks the ONE active strategy via the
 * `karyo.work.dispatch-strategy` config knob (env `KARYO_WORK_DISPATCH_STRATEGY`, non-empty
 * default `"STRICT_PRIORITY"`) matched against [name] — an unregistered configured name is a
 * hard failure, never a silent fallback to whatever else happens to be registered.
 *
 * [priority] no longer participates in that choice; before St6 the resolver picked
 * `strategies.minByOrNull { it.priority }`, which meant ANY third-party bean registered below
 * [Int.MAX_VALUE] (the built-in's value) silently became the active strategy app-wide the
 * moment it was deployed — exactly the failure this knob closes, the same class of bug
 * [com.karyo.stocktaking.spi.CountScopeStrategy] fixed for count-scope selection in St5.
 * [priority] is kept only as descriptive registry metadata (see the built-ins' own KDoc for what
 * they set it to). It is NOT a tie-break: the resolver takes `firstOrNull` among the beans
 * answering to the configured [name], so two beans registering under the same name resolve in
 * CDI discovery order, whatever their [priority]. That is a configuration mistake this interface
 * does not guard against — do not register two strategies under one name.
 */
interface WorkDispatchStrategy {
    val priority: Int
    val name: String
    fun order(items: List<WorkItem>): List<WorkItem>
}
