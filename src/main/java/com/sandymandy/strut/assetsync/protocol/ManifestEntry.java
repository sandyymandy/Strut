package com.sandymandy.strut.assetsync.protocol;

/**
 * One file in a manifest
 *
 * @param path {@code <namespace>/<path>}, see {@link AssetPath}
 * @param sha256 Lower-case hex SHA-256 of the file's bytes
 * @param size The file's length in bytes
 */
public record ManifestEntry(String path, String sha256, long size) {}
