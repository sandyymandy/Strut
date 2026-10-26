package com.sandymandy.strut.assetsync.protocol;

/**
 * Size caps for what may be synced
 *
 * @param maxFileBytes The largest single file
 */
public record SyncLimits(long maxFileBytes) {
	public static final SyncLimits DEFAULT = new SyncLimits(64L * 1024 * 1024);
}
