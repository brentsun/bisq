/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.governance.merit;

import bisq.core.dao.governance.voteresult.VoteResultException;
import bisq.core.dao.state.BsqStateService;
import bisq.core.dao.state.blockchain.Tx;
import bisq.core.dao.state.governance.Issuance;

import bisq.common.crypto.Encryption;
import bisq.common.util.Utilities;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;

import com.google.common.annotations.VisibleForTesting;

import javax.crypto.SecretKey;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class MeritConsensus {
    // Value with 144 blocks a day and 365 days would be 52560. We take a close round number instead.
    private static final int BLOCKS_PER_YEAR = 50_000;

    public static MeritList decryptMeritList(byte[] encryptedMeritList, SecretKey secretKey) throws VoteResultException {
        try {
            final byte[] decrypted = Encryption.decrypt(encryptedMeritList, secretKey);
            return MeritList.getMeritListFromBytes(decrypted);
        } catch (Throwable t) {
            throw new VoteResultException(t);
        }
    }

    public static long getMeritStake(String blindVoteTxId, MeritList meritList, BsqStateService bsqStateService) {
        int txChainHeight = bsqStateService.getTx(blindVoteTxId).map(Tx::getBlockHeight).orElse(0);
        return getMeritStake(blindVoteTxId, meritList, txChainHeight);
    }

    public static long getMeritStake(String blindVoteTxId, MeritList meritList, int txChainHeight) {
        // We need to take the chain height when the blindVoteTx got published so we get the same merit for the vote even at
        // later blocks (merit decreases with each block).
        if (txChainHeight == 0) {
            log.error("Error at getMeritStake: blindVoteTx not found in bsqStateService. blindVoteTxId=" + blindVoteTxId);
            return 0;
        }

        return meritList.getList().stream()
                .filter(merit -> isSignatureValid(merit.getSignature(), merit.getIssuance().getPubKey(), blindVoteTxId))
                .mapToLong(merit -> {
                    try {
                        return getWeightedMeritAmount(merit.getIssuance().getAmount(),
                                merit.getIssuance().getChainHeight(),
                                txChainHeight,
                                BLOCKS_PER_YEAR);
                    } catch (Throwable t) {
                        log.error("Error at getMeritStake: error={}, merit={}", t.toString(), merit);
                        return 0;
                    }
                })
                .sum();
    }

    @VisibleForTesting
    static boolean isSignatureValid(byte[] signatureFromMerit, String pubKeyAsHex, String blindVoteTxId) {
        // We verify if signature of hash of blindVoteTxId is correct. EC key from first input for blind vote tx is
        // used for signature.
        if (pubKeyAsHex == null) {
            log.error("Error at isSignatureValid: pubKeyAsHex is null");
            return false;
        }

        // TODO Check if a sig key was used multiple times for different voters
        // At the moment we don't impl. that to not add too much complexity and as we consider that
        // risk very low.

        boolean result = false;
        try {
            ECKey pubKey = ECKey.fromPublicOnly(Utilities.decodeFromHex(pubKeyAsHex));
            ECKey.ECDSASignature signature = ECKey.ECDSASignature.decodeFromDER(signatureFromMerit).toCanonicalised();
            Sha256Hash msg = Sha256Hash.wrap(blindVoteTxId);
            result = pubKey.verify(msg, signature);
        } catch (Throwable t) {
            log.error("Signature verification of issuance failed: " + t.toString());
        }
        if (!result) {
            log.error("Signature verification of issuance failed: blindVoteTxId={}, pubKeyAsHex={}",
                    blindVoteTxId, pubKeyAsHex);
        }
        return result;
    }

    public static long getWeightedMeritAmount(long amount, int issuanceHeight, int blockHeight, int blocksPerYear) {
        if (issuanceHeight > blockHeight)
            throw new IllegalArgumentException("issuanceHeight must not be larger than blockHeight. issuanceHeight=" + issuanceHeight + "; blockHeight=" + blockHeight);
        if (blockHeight < 0)
            throw new IllegalArgumentException("blockHeight must not be negative. blockHeight=" + blockHeight);
        if (amount < 0)
            throw new IllegalArgumentException("amount must not be negative. amount" + amount);
        if (blocksPerYear < 0)
            throw new IllegalArgumentException("blocksPerYear must not be negative. blocksPerYear=" + blocksPerYear);
        if (issuanceHeight < 0)
            throw new IllegalArgumentException("issuanceHeight must not be negative. issuanceHeight=" + issuanceHeight);

        // We use a linear function  to apply a factor for the issuance amount of 1 if the issuance was recent and 0
        // if the issuance was 2 years old or older.
        // To avoid rounding issues with double values we multiply initially with a large number and divide at the end
        // by that number again. As we multiply the amount in satoshi we get a reasonable good precision even the long
        // division is not using rounding. Sticking with long values makes that operation more safe against consensus
        // failures causes by rounding differences with double.

        long maxAge = 2 * blocksPerYear; // maxAge=100 000 (MeritConsensus.BLOCKS_PER_YEAR is 50_000)
        long age = Math.min(maxAge, blockHeight - issuanceHeight);
        long inverseAge = maxAge - age;

        // We want a resolution of 1 block so we use the inverseAge and divide by maxAge afterwards to get the
        // weighted amount
        // We need to multiply first before we divide!
        long weightedAmount = (amount * inverseAge) / maxAge;

        log.debug("getWeightedMeritAmount: age={}, inverseAge={}, weightedAmount={}, amount={}", age, inverseAge, weightedAmount, amount);
        return weightedAmount;
    }

    public static long getCurrentlyAvailableMerit(MeritList meritList, int currentChainHeight) {
        // We need to take the chain height when the blindVoteTx got published so we get the same merit for the vote even at
        // later blocks (merit decreases with each block).
        return meritList.getList().stream()
                .mapToLong(merit -> {
                    try {
                        Issuance issuance = merit.getIssuance();
                        int issuanceHeight = issuance.getChainHeight();
                        checkArgument(issuanceHeight <= currentChainHeight,
                                "issuanceHeight must not be larger as currentChainHeight");
                        return getWeightedMeritAmount(issuance.getAmount(),
                                issuanceHeight,
                                currentChainHeight,
                                BLOCKS_PER_YEAR);
                    } catch (Throwable t) {
                        log.error("Error at getCurrentlyAvailableMerit: " + t.toString());
                        return 0;
                    }
                })
                .sum();
    }

}
