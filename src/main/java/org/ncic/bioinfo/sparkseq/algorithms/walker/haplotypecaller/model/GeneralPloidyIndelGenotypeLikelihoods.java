/*
 * Copyright (c) 2017 NCIC, Institute of Computing Technology, Chinese Academy of Sciences
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model;

import htsjdk.variant.variantcontext.Allele;
import org.ncic.bioinfo.sparkseq.algorithms.utils.MathUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.haplotype.Haplotype;
import org.ncic.bioinfo.sparkseq.algorithms.utils.pairhmm.PairHMMIndelErrorModel;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.PileupElement;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.ReadBackedPileup;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.GeneralPloidyGenotypeLikelihoods;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.afcalculate.ExactACset;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.argcollection.UnifiedArgumentCollection;
import org.ncic.bioinfo.sparkseq.exceptions.ReviewedGATKException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Author: wbc
 */
public class GeneralPloidyIndelGenotypeLikelihoods extends GeneralPloidyGenotypeLikelihoods {
    final PairHMMIndelErrorModel pairModel;
    final LinkedHashMap<Allele, Haplotype> haplotypeMap;
    final ReferenceContext refContext;
    final int eventLength;
    double[][] readHaplotypeLikelihoods;

    final byte refBase;
    final PerReadAlleleLikelihoodMap perReadAlleleLikelihoodMap;

    public GeneralPloidyIndelGenotypeLikelihoods(final List<Allele> alleles,
                                                 final double[] logLikelihoods,
                                                 final int ploidy,
                                                 final HashMap<String, ErrorModel> perLaneErrorModels,
                                                 final boolean ignoreLaneInformation,
                                                 final PairHMMIndelErrorModel pairModel,
                                                 final LinkedHashMap<Allele, Haplotype> haplotypeMap,
                                                 final ReferenceContext referenceContext,
                                                 final PerReadAlleleLikelihoodMap perReadAlleleLikelihoodMap) {
        super(alleles, logLikelihoods, ploidy, perLaneErrorModels, ignoreLaneInformation);
        this.pairModel = pairModel;
        this.haplotypeMap = haplotypeMap;
        this.refContext = referenceContext;
        this.eventLength = IndelGenotypeLikelihoodsCalculationModel.getEventLength(alleles);
        // todo - not needed if indel alleles have base at current position
        this.refBase = referenceContext.getBase();
        this.perReadAlleleLikelihoodMap = perReadAlleleLikelihoodMap;
    }

    // -------------------------------------------------------------------------------------
    //
    // add() routines.  These are the workhorse routines for calculating the overall genotype
    // likelihoods given observed bases and reads.  Includes high-level operators all the
    // way down to single base and qual functions.
    //
    // -------------------------------------------------------------------------------------

    /**
     * Updates likelihoods and posteriors to reflect the additional observations contained within the
     * read-based pileup up by calling add(observedBase, qualityScore) for each base / qual in the
     * pileup
     *
     * @param pileup                    read pileup
     * @param UAC                      the minimum base quality at which to consider a base valid
     * @return the number of good bases found in the pileup
     */
    public int add(ReadBackedPileup pileup, UnifiedArgumentCollection UAC) {
        int n = 0;

        if (!hasReferenceSampleData) {
            // no error models
            return add(pileup, (ErrorModel)null);
        }
        for (String laneID : perLaneErrorModels.keySet() ) {
            // get pileup for this lane
            ReadBackedPileup perLanePileup;
            if (ignoreLaneInformation)
                perLanePileup = pileup;
            else
                perLanePileup = pileup.getPileupForLane(laneID);

            if (perLanePileup == null || perLanePileup.isEmpty())
                continue;

            ErrorModel errorModel = perLaneErrorModels.get(laneID);
            n += add(perLanePileup, errorModel);
            if (ignoreLaneInformation)
                break;

        }

        return n;
    }

    /**
     * Calculates the pool's probability for all possible allele counts for all indel alleles observed.
     * Calculation is based on the error model
     * generated by the reference sample on the same lane. The probability is given by :
     *
     * Pr(ac = j1,j2,.. | pool, errorModel) = sum_over_all_Qs ( Pr(j1,j2,.. * Pr(errorModel_q) *
     * Pr(ac=j1,j2,..| pool, errorModel) = sum_over_all_Qs ( Pr(ac=j1,j2,..) * Pr(errorModel_q) *
     * [j1 * (1-eq)/2n + eq/3*(2*N-j1)
     * [jA*(1-eq)/2n + eq/3*(jc+jg+jt)/2N)^nA *   jC*(1-eq)/2n + eq/3*(ja+jg+jt)/2N)^nC *
     * jG*(1-eq)/2n + eq/3*(jc+ja+jt)/2N)^nG * jT*(1-eq)/2n + eq/3*(jc+jg+ja)/2N)^nT
     *
     *  log Pr(ac=jA,jC,jG,jT| pool, errorModel) = logsum( Pr(ac=jA,jC,jG,jT) * Pr(errorModel_q) *
     * [jA*(1-eq)/2n + eq/3*(jc+jg+jt)/2N)^nA *   jC*(1-eq)/2n + eq/3*(ja+jg+jt)/2N)^nC *
     * jG*(1-eq)/2n + eq/3*(jc+ja+jt)/2N)^nG * jT*(1-eq)/2n + eq/3*(jc+jg+ja)/2N)^nT)
     * = logsum(logPr(ac=jA,jC,jG,jT) + log(Pr(error_Model(q)
     * )) + nA*log(jA/2N(1-eq)+eq/3*(2N-jA)/2N) + nC*log(jC/2N(1-eq)+eq/3*(2N-jC)/2N)
     * + log(jG/2N(1-eq)+eq/3*(2N-jG)/2N) + log(jT/2N(1-eq)+eq/3*(2N-jT)/2N)
     *
     * Let Q(j,k) = log(j/2N*(1-e[k]) + (2N-j)/2N*e[k]/3)
     *
     * Then logPr(ac=jA,jC,jG,jT|D,errorModel) = logPR(ac=Ja,jC,jG,jT) + logsum_k( logPr (errorModel[k],
     * nA*Q(jA,k) +  nC*Q(jC,k) + nG*Q(jG,k) + nT*Q(jT,k))
     *
     * If pileup data comes from several error models (because lanes can have different error models),
     * Pr(Ac=j|D,E1,E2) = sum(Pr(AC1=j1|D,E1,E2) * Pr(AC2=j-j2|D,E1,E2))
     * = sum(Pr(AC1=j1|D,E1)*Pr(AC2=j-j1|D,E2)) from j=0..2N
     *
     * So, for each lane, build error model and combine lanes.
     * To store model, can do
     * for jA=0:2N
     *  for jC = 0:2N-jA
     *   for jG = 0:2N-jA-jC
     *    for jT = 0:2N-jA-jC-jG
     *      Q(jA,jC,jG,jT)
     *      for k = minSiteQual:maxSiteQual
     *        likelihood(jA,jC,jG,jT) = logsum(logPr (errorModel[k],nA*Q(jA,k) +  nC*Q(jC,k) + nG*Q(jG,k) + nT*Q(jT,k))
     *
     *
     *
     * where: nA,nC,nG,nT = counts of bases observed in pileup.
     *
     *
     * @param pileup                            Base pileup
     * @param errorModel                        Site error model
     * @return                                  Number of bases added
     */
    private int add(ReadBackedPileup pileup, ErrorModel errorModel) {
        int n=0;

        // Number of alleless in pileup, in that order
        List<Integer> numSeenBases = new ArrayList<Integer>(this.alleles.size());

        if (!hasReferenceSampleData) {

            readHaplotypeLikelihoods = pairModel.computeGeneralReadHaplotypeLikelihoods(pileup, haplotypeMap, refContext, eventLength, perReadAlleleLikelihoodMap);
            n = readHaplotypeLikelihoods.length;
        } else {
            Allele refAllele = null;
            for (Allele a:alleles) {
                numSeenBases.add(0);
                if (a.isReference())
                    refAllele = a;
            }

            if (refAllele == null)
                throw new ReviewedGATKException("BUG: no ref alleles in passed in allele list!");

            // count number of elements in pileup
            for (PileupElement elt : pileup) {
                if (VERBOSE)
                    System.out.format("base:%s isNextToDel:%b isNextToIns:%b eventBases:%s eventLength:%d\n",elt.getBase(), elt.isBeforeDeletionStart(),elt.isBeforeInsertion(),elt.getBasesOfImmediatelyFollowingInsertion(),elt.getLengthOfImmediatelyFollowingIndel());
                int idx =0;
                for (Allele allele : alleles) {
                    int cnt = numSeenBases.get(idx);
                    numSeenBases.set(idx++,cnt + (ErrorModel.pileupElementMatches(elt, allele, refAllele, refBase)?1:0));
                }

                n++;

            }
        }
        computeLikelihoods(errorModel, alleles, numSeenBases, pileup);
        return n;
    }



    /**
     * Compute likelihood of current conformation
     *
     * @param ACset       Count to compute
     * @param errorModel    Site-specific error model object
     * @param alleleList    List of alleles
     * @param numObservations Number of observations for each allele in alleleList
     */
    public void getLikelihoodOfConformation(final ExactACset ACset,
                                            final ErrorModel errorModel,
                                            final List<Allele> alleleList,
                                            final List<Integer> numObservations,
                                            final ReadBackedPileup pileup) {
        final int[] currentCnt = Arrays.copyOf(ACset.getACcounts().getCounts(), alleleList.size());
        double p1 = 0.0;

        if (!hasReferenceSampleData) {
            // no error model: use pair HMM likelihoods
            for (int i=0; i < readHaplotypeLikelihoods.length; i++) {
                double acc[] = new double[alleleList.size()];
                for (int k=0; k < acc.length; k++ )
                    acc[k] = readHaplotypeLikelihoods[i][k] + MathUtils.Log10Cache.get(currentCnt[k])-LOG10_PLOIDY;
                p1 += MathUtils.log10sumLog10(acc);
            }

        } else {
            final int minQ = errorModel.getMinSignificantQualityScore();
            final int maxQ = errorModel.getMaxSignificantQualityScore();
            final double[] acVec = new double[maxQ - minQ + 1];


            for (int k=minQ; k<=maxQ; k++) {
                int idx=0;
                for (int n : numObservations)
                    acVec[k-minQ] += n*logMismatchProbabilityArray[currentCnt[idx++]][k];
            }
            p1 = MathUtils.logDotProduct(errorModel.getErrorModelVector().getProbabilityVector(minQ, maxQ), acVec);
        }
        ACset.getLog10Likelihoods()[0] = p1;
    }
}
