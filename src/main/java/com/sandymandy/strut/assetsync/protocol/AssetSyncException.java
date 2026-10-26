package com.sandymandy.strut.assetsync.protocol;

/**
 * Something about a synced asset was wrong: a bad hash, an out-of-order chunk, an oversize file
 */
public final class AssetSyncException extends RuntimeException {
	public AssetSyncException(String message) {
		super(message);
	}

	public AssetSyncException(String message, Throwable cause) {
		super(message, cause);
	}
}
