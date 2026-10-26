package com.sandymandy.strut.loading.binary;

/**
 * Thrown when a Strut binary asset cannot be read
 * <p>
 * Every message names the file it came from and the field being read when the read failed,
 * because the realistic causes - a truncated download, a file renamed to the wrong extension,
 * or an exporter that has drifted from the reader - are indistinguishable from each other
 * without that context
 */
public class BinaryFormatException extends RuntimeException {
	public BinaryFormatException(String message) {
		super(message);
	}

	public BinaryFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
