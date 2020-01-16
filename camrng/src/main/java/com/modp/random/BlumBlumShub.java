/*
 * Copyright (c) 2020 Andika Wasisto
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.modp.random;
import java.util.Random;
import java.security.SecureRandom;
import java.math.BigInteger;

/**
 * The Blum-Blum-Shub random number generator.
 *
 * <p>
 * The Blum-Blum-Shub is a "cryptographically secure" random number
 * generator.  It has been proven that predicting the ouput
 * is equivalent to factoring <i>n</i>, a large integer generated
 * from two prime numbers.
 * </p>
 *
 * <p>
 * The Algorithm:
 * </p>
 * <ol>
 * <li>
 *  (setup) generate two secret prime numbers <i>p</i>, <i>q</i> such that
 *   <i>p</i> &ne; <i>q</i>, <i>p</i> &equiv; 3 mod 4, <i>q</i> &equiv; 3 mod 4.
 * </li>
 * <li> (setup) compute <i>n</i> = <i>pq</i>.  <i>n</i> can be re-used, but
 *   <i>p</i>, and <i>q</i> are secret and should be disposed of.</li>
 * <li> Generate a (secure) random seed <i>s</i> in the range [1, <i>n</i> -1]
 *   such that gcd(<i>s</i>, <i>n</i>) = 1.
 * <li> Compute <i>x</i> = <i>s</i><sup>2</sup> mod <i>n</i></li>
 * <li> Compute a single random bit with:
 *   <ol>
 *   <li> <i>x</i> = <i>x</i><sup>2</sup> mod <i>n</i></li>
 *   <li> return Least-Significant-Bit(<i>x</i>) (i.e. <i>x</i> & 1)</li>
 *   </ol>
 * Repeat as necessary.
 * </li>
 * </ol>
 *
 * <p>
 * The code originally appeared in <a href="http://modp.com/cida/"><i>Cryptography for
 * Internet and Database Applications </i>, Chapter 4, pages 174-177</a>
 * </p>
 * <p>
 * More details are in  the <a href="http://www.cacr.math.uwaterloo.ca/hac/"><i>Handbook of Applied Cryptography</i></a>,
 * <a href="http://www.cacr.math.uwaterloo.ca/hac/about/chap5.pdf">Section 5.5.2</a>
 * </p>
 *
 * @author Nick Galbreath -- nickg [at] modp [dot] com
 * @version 3 -- 06-Jul-2005
 */
public class BlumBlumShub implements RandomGenerator {

    // pre-compute a few values
    private static final BigInteger two = BigInteger.valueOf(2L);

    private static final BigInteger three = BigInteger.valueOf(3L);

    private static final BigInteger four = BigInteger.valueOf(4L);

    /**
     * main parameter
     */
    private BigInteger n;

    private BigInteger state;

    /**
     * Generate appropriate prime number for use in Blum-Blum-Shub.
     *
     * This generates the appropriate primes (p = 3 mod 4) needed to compute the
     * "n-value" for Blum-Blum-Shub.
     *
     * @param bits Number of bits in prime
     * @param rand A source of randomness
     */
    private static BigInteger getPrime(int bits, Random rand) {
        BigInteger p;
        while (true) {
            p = new BigInteger(bits, 100, rand);
            if (p.mod(four).equals(three))
                break;
        }
        return p;
    }

    /**
     * This generates the "n value" -- the multiplication of two equally sized
     * random prime numbers -- for use in the Blum-Blum-Shub algorithm.
     *
     * @param bits
     *            The number of bits of security
     * @param rand
     *            A random instance to aid in generating primes
     * @return A BigInteger, the <i>n</i>.
     */
    public static BigInteger generateN(int bits, Random rand) {
        BigInteger p = getPrime(bits/2, rand);
        BigInteger q = getPrime(bits/2, rand);

        // make sure p != q (almost always true, but just in case, check)
        while (p.equals(q)) {
            q = getPrime(bits, rand);
        }
        return p.multiply(q);
    }

    /**
     * Constructor, specifing bits for <i>n</i>
     *
     * @param bits number of bits
     */
    public BlumBlumShub(int bits) {
        this(bits, new Random());
    }

    /**
     * Constructor, generates prime and seed
     *
     * @param bits
     * @param rand
     */
    public BlumBlumShub(int bits, Random rand) {
        this(generateN(bits, rand));
    }

    /**
     * A constructor to specify the "n-value" to the Blum-Blum-Shub algorithm.
     * The inital seed is computed using Java's internal "true" random number
     * generator.
     *
     * @param n
     *            The n-value.
     */
    public BlumBlumShub(BigInteger n) {
        this(n, SecureRandom.getSeed(n.bitLength() / 8));
    }

    /**
     * A constructor to specify both the n-value and the seed to the
     * Blum-Blum-Shub algorithm.
     *
     * @param n
     *            The n-value using a BigInteger
     * @param seed
     *            The seed value using a byte[] array.
     */
    public BlumBlumShub(BigInteger n, byte[] seed) {
        this.n = n;
        setSeed(seed);
    }

    /**
     * Sets or resets the seed value and internal state
     *
     * @param seedBytes
     *            The new seed.
     */
    public void setSeed(byte[] seedBytes) {
        // ADD: use hardwired default for n
        BigInteger seed = new BigInteger(1, seedBytes);
        state = seed.mod(n);
    }

    /**
     * Returns up to numBit random bits
     *
     * @return int
     */
    public int next(int numBits) {
        // TODO: find out how many LSB one can extract per cycle.
        //   it is more than one.
        int result = 0;
        for (int i = numBits; i != 0; --i) {
            state = state.modPow(two, n);
            result = (result << 1) | (state.testBit(0) == true ? 1 : 0);
        }
        return result;
    }
}
