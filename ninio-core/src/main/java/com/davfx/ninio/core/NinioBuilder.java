package com.davfx.ninio.core;

public interface NinioBuilder<T> {
	T create(NinioProvider ninioProvider);
}
