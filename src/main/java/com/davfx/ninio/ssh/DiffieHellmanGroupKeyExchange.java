/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/*
 Copyright (c) 2002-2014 ymnk, JCraft,Inc. All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice,
 this list of conditions and the following disclaimer.

 2. Redistributions in binary form must reproduce the above copyright 
 notice, this list of conditions and the following disclaimer in 
 the documentation and/or other materials provided with the distribution.

 3. The names of the authors may not be used to endorse or promote products
 derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
 INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT,
 INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.davfx.ninio.ssh;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

final class DiffieHellmanGroupKeyExchange {
	public static final int GROUP_EXCHANGE_MIN = 1024;
	public static final int GROUP_EXCHANGE_MAX = 1024;
	public static final int GROUP_EXCHANGE_PREFERRED = 1024;
	
	public static final class DiffieHellmanGroup1 {
		private DiffieHellmanGroup1() {
		}
		public static final byte[] g = { 2 };
		public static final byte[] p = {
			(byte)0x00,
			(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF, 
			(byte)0xC9,(byte)0x0F,(byte)0xDA,(byte)0xA2,(byte)0x21,(byte)0x68,(byte)0xC2,(byte)0x34,
			(byte)0xC4,(byte)0xC6,(byte)0x62,(byte)0x8B,(byte)0x80,(byte)0xDC,(byte)0x1C,(byte)0xD1,
			(byte)0x29,(byte)0x02,(byte)0x4E,(byte)0x08,(byte)0x8A,(byte)0x67,(byte)0xCC,(byte)0x74,
			(byte)0x02,(byte)0x0B,(byte)0xBE,(byte)0xA6,(byte)0x3B,(byte)0x13,(byte)0x9B,(byte)0x22,
			(byte)0x51,(byte)0x4A,(byte)0x08,(byte)0x79,(byte)0x8E,(byte)0x34,(byte)0x04,(byte)0xDD,
			(byte)0xEF,(byte)0x95,(byte)0x19,(byte)0xB3,(byte)0xCD,(byte)0x3A,(byte)0x43,(byte)0x1B,
			(byte)0x30,(byte)0x2B,(byte)0x0A,(byte)0x6D,(byte)0xF2,(byte)0x5F,(byte)0x14,(byte)0x37,
			(byte)0x4F,(byte)0xE1,(byte)0x35,(byte)0x6D,(byte)0x6D,(byte)0x51,(byte)0xC2,(byte)0x45,
			(byte)0xE4,(byte)0x85,(byte)0xB5,(byte)0x76,(byte)0x62,(byte)0x5E,(byte)0x7E,(byte)0xC6,
			(byte)0xF4,(byte)0x4C,(byte)0x42,(byte)0xE9,(byte)0xA6,(byte)0x37,(byte)0xED,(byte)0x6B,
			(byte)0x0B,(byte)0xFF,(byte)0x5C,(byte)0xB6,(byte)0xF4,(byte)0x06,(byte)0xB7,(byte)0xED,
			(byte)0xEE,(byte)0x38,(byte)0x6B,(byte)0xFB,(byte)0x5A,(byte)0x89,(byte)0x9F,(byte)0xA5,
			(byte)0xAE,(byte)0x9F,(byte)0x24,(byte)0x11,(byte)0x7C,(byte)0x4B,(byte)0x1F,(byte)0xE6,
			(byte)0x49,(byte)0x28,(byte)0x66,(byte)0x51,(byte)0xEC,(byte)0xE6,(byte)0x53,(byte)0x81,
			(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
		};
	}
	
	public static final class DiffieHellmanGroup14 {
		private DiffieHellmanGroup14() {
		}
		public static final byte[] g = { 2 };
		public static final byte[] p = {
			(byte)0x00,
			(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,
			(byte)0xC9,(byte)0x0F,(byte)0xDA,(byte)0xA2,(byte)0x21,(byte)0x68,(byte)0xC2,(byte)0x34,
			(byte)0xC4,(byte)0xC6,(byte)0x62,(byte)0x8B,(byte)0x80,(byte)0xDC,(byte)0x1C,(byte)0xD1,
			(byte)0x29,(byte)0x02,(byte)0x4E,(byte)0x08,(byte)0x8A,(byte)0x67,(byte)0xCC,(byte)0x74,
			(byte)0x02,(byte)0x0B,(byte)0xBE,(byte)0xA6,(byte)0x3B,(byte)0x13,(byte)0x9B,(byte)0x22,
			(byte)0x51,(byte)0x4A,(byte)0x08,(byte)0x79,(byte)0x8E,(byte)0x34,(byte)0x04,(byte)0xDD,
			(byte)0xEF,(byte)0x95,(byte)0x19,(byte)0xB3,(byte)0xCD,(byte)0x3A,(byte)0x43,(byte)0x1B,
			(byte)0x30,(byte)0x2B,(byte)0x0A,(byte)0x6D,(byte)0xF2,(byte)0x5F,(byte)0x14,(byte)0x37,
			(byte)0x4F,(byte)0xE1,(byte)0x35,(byte)0x6D,(byte)0x6D,(byte)0x51,(byte)0xC2,(byte)0x45,
			(byte)0xE4,(byte)0x85,(byte)0xB5,(byte)0x76,(byte)0x62,(byte)0x5E,(byte)0x7E,(byte)0xC6,
			(byte)0xF4,(byte)0x4C,(byte)0x42,(byte)0xE9,(byte)0xA6,(byte)0x37,(byte)0xED,(byte)0x6B,
			(byte)0x0B,(byte)0xFF,(byte)0x5C,(byte)0xB6,(byte)0xF4,(byte)0x06,(byte)0xB7,(byte)0xED,
			(byte)0xEE,(byte)0x38,(byte)0x6B,(byte)0xFB,(byte)0x5A,(byte)0x89,(byte)0x9F,(byte)0xA5,
			(byte)0xAE,(byte)0x9F,(byte)0x24,(byte)0x11,(byte)0x7C,(byte)0x4B,(byte)0x1F,(byte)0xE6,
			(byte)0x49,(byte)0x28,(byte)0x66,(byte)0x51,(byte)0xEC,(byte)0xE4,(byte)0x5B,(byte)0x3D,
			(byte)0xC2,(byte)0x00,(byte)0x7C,(byte)0xB8,(byte)0xA1,(byte)0x63,(byte)0xBF,(byte)0x05,
			(byte)0x98,(byte)0xDA,(byte)0x48,(byte)0x36,(byte)0x1C,(byte)0x55,(byte)0xD3,(byte)0x9A,
			(byte)0x69,(byte)0x16,(byte)0x3F,(byte)0xA8,(byte)0xFD,(byte)0x24,(byte)0xCF,(byte)0x5F,
			(byte)0x83,(byte)0x65,(byte)0x5D,(byte)0x23,(byte)0xDC,(byte)0xA3,(byte)0xAD,(byte)0x96,
			(byte)0x1C,(byte)0x62,(byte)0xF3,(byte)0x56,(byte)0x20,(byte)0x85,(byte)0x52,(byte)0xBB,
			(byte)0x9E,(byte)0xD5,(byte)0x29,(byte)0x07,(byte)0x70,(byte)0x96,(byte)0x96,(byte)0x6D,
			(byte)0x67,(byte)0x0C,(byte)0x35,(byte)0x4E,(byte)0x4A,(byte)0xBC,(byte)0x98,(byte)0x04,
			(byte)0xF1,(byte)0x74,(byte)0x6C,(byte)0x08,(byte)0xCA,(byte)0x18,(byte)0x21,(byte)0x7C,
			(byte)0x32,(byte)0x90,(byte)0x5E,(byte)0x46,(byte)0x2E,(byte)0x36,(byte)0xCE,(byte)0x3B,
			(byte)0xE3,(byte)0x9E,(byte)0x77,(byte)0x2C,(byte)0x18,(byte)0x0E,(byte)0x86,(byte)0x03,
			(byte)0x9B,(byte)0x27,(byte)0x83,(byte)0xA2,(byte)0xEC,(byte)0x07,(byte)0xA2,(byte)0x8F,
			(byte)0xB5,(byte)0xC5,(byte)0x5D,(byte)0xF0,(byte)0x6F,(byte)0x4C,(byte)0x52,(byte)0xC9,
			(byte)0xDE,(byte)0x2B,(byte)0xCB,(byte)0xF6,(byte)0x95,(byte)0x58,(byte)0x17,(byte)0x18,
			(byte)0x39,(byte)0x95,(byte)0x49,(byte)0x7C,(byte)0xEA,(byte)0x95,(byte)0x6A,(byte)0xE5,
			(byte)0x15,(byte)0xD2,(byte)0x26,(byte)0x18,(byte)0x98,(byte)0xFA,(byte)0x05,(byte)0x10,
			(byte)0x15,(byte)0x72,(byte)0x8E,(byte)0x5A,(byte)0x8A,(byte)0xAC,(byte)0xAA,(byte)0x68,
			(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
		};
	}
	
	private byte[] e;
	private byte[] p;
	private byte[] g;
	private KeyAgreement agreement;

	public DiffieHellmanGroupKeyExchange() {
	}
	
	public void init(byte[] p, byte[] g) throws GeneralSecurityException {
		this.p = p;
		this.g = g;
		
		agreement = KeyAgreement.getInstance("DH");
		KeyPairGenerator generator = KeyPairGenerator.getInstance("DH");

		generator.initialize(new DHParameterSpec(new BigInteger(p), new BigInteger(g)));
		KeyPair pair = generator.generateKeyPair();
		agreement.init(pair.getPrivate());
		e = ((DHPublicKey) pair.getPublic()).getY().toByteArray();
	}
	
	public byte[] getK(byte[] f) throws GeneralSecurityException {
		PublicKey yourPubKey = KeyFactory.getInstance("DH").generatePublic(new DHPublicKeySpec(new BigInteger(f), new BigInteger(p), new BigInteger(g)));
		agreement.doPhase(yourPubKey, true);
		return agreement.generateSecret();
	}

	public byte[] getE() throws GeneralSecurityException {
		return e;
	}
}