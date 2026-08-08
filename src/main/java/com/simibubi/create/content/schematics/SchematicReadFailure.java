package com.simibubi.create.content.schematics;

import java.io.EOFException;

import net.minecraft.ReportedException;

final class SchematicReadFailure {

	private SchematicReadFailure() {}

	static boolean isUnexpectedEOF(ReportedException exception) {
		Throwable current = exception;
		for (int depth = 0; current != null && depth < 16; depth++) {
			if (current instanceof EOFException)
				return true;
			Throwable next = current.getCause();
			if (next == current)
				break;
			current = next;
		}
		return false;
	}
}
