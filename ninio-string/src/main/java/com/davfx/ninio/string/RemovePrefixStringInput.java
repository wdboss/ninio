package com.davfx.ninio.string;

final class RemovePrefixStringInput<T> implements StringInput<T> {
	private final StringInput<T> prefix;
	private final StringInput<T> wrappee;

	public RemovePrefixStringInput(StringInput<T> prefix, StringInput<T> wrappee) {
		this.prefix = prefix;
		this.wrappee = wrappee;
	}
	@Override
	public String get(T h) {
		String s = wrappee.get(h);
		if (s == null) {
			return null;
		}
		String p = prefix.get(h);
		if (p == null) {
			return null;
		}
		if (s.startsWith(p)) {
			return s.substring(p.length());
		} else {
			return s;
		}
	}
}
