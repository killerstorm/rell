/*
 * Copyright (c) 2021 EmeraldPay Inc, All Rights Reserved.
 * Copyright (c) 2016-2019 Igor Artamonov, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Source:
// https://github.com/emeraldpay/etherjar/blob/68e46c8fc3a996bdb5a0ff384770fe6108201f37/etherjar-tx/src/main/java/io/emeraldpay/etherjar/tx/Signature.java

package net.postchain.rell.utils.etherjar;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Signature of a message (i.e. of a transaction)
 */
public class Signature {

    private byte[] message;

    private int v;
    private BigInteger r;
    private BigInteger s;

    public Signature() {
    }

    /**
     * Creates existing signature
     *
     * @param message a signed message, usually a Keccak256 of some data
     * @param v v part of signature
     * @param r R part of signature
     * @param s S part of signature
     */
    public Signature(byte[] message, int v, BigInteger r, BigInteger s) {
        this.message = message;
        this.v = v;
        this.r = r;
        this.s = s;
    }

    public byte[] getMessage() {
        return message;
    }

    public void setMessage(byte[] message) {
        this.message = message;
    }

    public int getV() {
        return v;
    }

    public void setV(int v) {
        this.v = v;
    }

    public BigInteger getR() {
        return r;
    }

    public void setR(BigInteger r) {
        this.r = r;
    }

    public BigInteger getS() {
        return s;
    }

    public void setS(BigInteger s) {
        this.s = s;
    }

    public int getRecId() {
        return v - 27;
    }

    public boolean canEqual(Signature signature) {
        return v == signature.v
                && Arrays.equals(message, signature.message)
                && Objects.equals(r, signature.r)
                && Objects.equals(s, signature.s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Signature signature = (Signature) o;
        return canEqual(signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, s);
    }
}
