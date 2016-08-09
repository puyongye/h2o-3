package water.util;

import water.*;
import water.fvec.*;

import java.util.Arrays;
import java.util.Random;

import static water.util.RandomUtils.getRNG;

public class MRUtils {


  /**
   * Sample rows from a frame.
   * Can be unlucky for small sampling fractions - will continue calling itself until at least 1 row is returned.
   * @param vecs Input vecs
   * @param rows Approximate number of rows to sample (across all chunks)
   * @param seed Seed for RNG
   * @return Sampled frame
   */
  public static VecAry sampleVecs(VecAry vecs, final long rows, final long seed) {
    if (vecs == null) return null;
    final float fraction = rows > 0 ? (float)rows / vecs.numRows() : 1.f;
    if (fraction >= 1.f) return vecs;
    VecAry r = new MRTask() {
      @Override
      public void map(Chunk[] cs, NewChunk[] ncs) {
        final Random rng = getRNG(0);
        int count = 0;
        for (int r = 0; r < cs[0]._len; r++) {
          rng.setSeed(seed+r+cs[0].start());
          if (rng.nextFloat() < fraction || (count == 0 && r == cs[0]._len-1) ) {
            count++;
            for (int i = 0; i < ncs.length; i++) {
              ncs[i].addNum(cs[i].atd(r));
            }
          }
        }
      }
    }.doAll(vecs.types(), vecs).outputVecs(vecs.domains());
    if (r.numRows() == 0) {
      Log.warn("You asked for " + rows + " rows (out of " + vecs.numRows() + "), but you got none (seed=" + seed + ").");
      Log.warn("Let's try again. You've gotta ask yourself a question: \"Do I feel lucky?\"");
      return sampleVecs(vecs, rows, seed+1);
    }
    return r;
  }

  /**
   * Row-wise shuffle of a frame (only shuffles rows inside of each chunk)
   * @param vecs Input vecs
   * @return Shuffled vecs
   */
  public static VecAry shuffleVecsPerChunk(VecAry vecs, final long seed) {
    return new MRTask() {
      @Override
      public void map(Chunk[] cs, NewChunk[] ncs) {
        int[] idx = new int[cs[0]._len];
        for (int r=0; r<idx.length; ++r) idx[r] = r;
        ArrayUtils.shuffleArray(idx, getRNG(seed));
        for (long anIdx : idx) {
          for (int i = 0; i < ncs.length; i++) {
            ncs[i].addNum(cs[i].atd((int) anIdx));
          }
        }
      }
    }.doAll(vecs.len(), Vec.T_NUM, vecs).outputVecs(vecs.domains());
  }

  /**
   * Compute the class distribution from a class label vector
   * (not counting missing values)
   *
   * Usage 1: Label vector is categorical
   * ------------------------------------
   * Vec label = ...;
   * assert(label.isCategorical());
   * double[] dist = new ClassDist(label).doAll(label).dist();
   *
   * Usage 2: Label vector is numerical
   * ----------------------------------
   * Vec label = ...;
   * int num_classes = ...;
   * assert(label.isInt());
   * double[] dist = new ClassDist(num_classes).doAll(label).dist();
   *
   */
  public static class ClassDist extends MRTask<ClassDist> {
    final int _nclass;
    protected double[] _ys;
    public ClassDist(final VecAry label) { _nclass = label.domain(0).length; }
    public ClassDist(int n) { _nclass = n; }

    public final double[] dist() { return _ys; }
    public final double[] rel_dist() {
      final double sum = ArrayUtils.sum(_ys);
      return ArrayUtils.div(Arrays.copyOf(_ys, _ys.length), sum);
    }
    @Override public void map(Chunk ys) {
      _ys = new double[_nclass];
      for( int i=0; i<ys._len; i++ )
        if (!ys.isNA(i))
          _ys[(int) ys.at8(i)]++;
    }
    @Override public void map(Chunk ys, Chunk ws) {
      _ys = new double[_nclass];
      for( int i=0; i<ys._len; i++ )
        if (!ys.isNA(i))
          _ys[(int) ys.at8(i)] += ws.atd(i);
    }
    @Override public void reduce( ClassDist that ) { ArrayUtils.add(_ys,that._ys); }
  }

  public static class Dist extends MRTask<Dist> {
    private IcedHashMap<Double,Integer> _dist;
    @Override public void map(Chunk ys) {
      _dist = new IcedHashMap<>();
      for( int row=0; row< ys._len; row++ )
        if( !ys.isNA(row) ) {
          double v = ys.atd(row);
          Integer oldV = _dist.putIfAbsent(v,1);
          if( oldV!=null ) _dist.put(v,oldV+1);
        }
    }

    @Override public void reduce(Dist mrt) {
      if( _dist != mrt._dist ) {
        IcedHashMap<Double,Integer> l = _dist;
        IcedHashMap<Double,Integer> r = mrt._dist;
        if( l.size() < r.size() ) { l=r; r=_dist; }
        for( Double v: r.keySet() ) {
          Integer oldVal = l.putIfAbsent(v, r.get(v));
          if( oldVal!=null ) l.put(v, oldVal+r.get(v));
        }
        _dist=l;
        mrt._dist=null;
      }
    }
    public double[] dist() {
      int i=0;
      double[] dist = new double[_dist.size()];
      for( int v: _dist.values() ) dist[i++] = v;
      return dist;
    }
    public double[] keys() {
      int i=0;
      double[] keys = new double[_dist.size()];
      for( double v: _dist.keySet() ) keys[i++] = v;
      return keys;
    }
  }


  /**
   * Stratified sampling for classifiers - FIXME: For weights, this is not accurate, as the sampling is done with uniform weights
   * @param vecs Input vecs
   * @param labelId Label vector id (must be categorical)
   * @param weightsId Weights vector id or -1
   * @param sampling_ratios Optional: array containing the requested sampling ratios per class (in order of domains), will be overwritten if it contains all 0s
   * @param maxrows Maximum number of rows in the returned frame
   * @param seed RNG seed for sampling
   * @param allowOversampling Allow oversampling of minority classes
   * @param verbose Whether to print verbose info
   * @return Sampled frame, with approximately the same number of samples from each class (or given by the requested sampling ratios)
   */
  public static VecAry sampleFrameStratified(final VecAry vecs, int labelId, int weightsId, float[] sampling_ratios, long maxrows, final long seed, final boolean allowOversampling, final boolean verbose) {
    if (vecs == null) return null;
    assert(vecs.isCategorical(labelId));
    String [] ldom = vecs.domain(labelId);
    if (maxrows < ldom.length) {
      Log.warn("Attempting to do stratified sampling to fewer samples than there are class labels - automatically increasing to #rows == #labels (" + ldom.length + ").");
      maxrows = ldom.length;
    }
    ClassDist cd = new ClassDist(vecs.getVecs(labelId));
    double[] dist = weightsId != -1 ? cd.doAll(vecs.getVecs(labelId,weightsId)).dist() : cd.doAll(vecs.getVecs(labelId)).dist();
    assert(dist.length > 0);
    Log.info("Doing stratified sampling for data set containing " + vecs.numRows() + " rows from " + dist.length + " classes. Oversampling: " + (allowOversampling ? "on" : "off"));
    if (verbose)
      for (int i=0; i<dist.length;++i)
        Log.info("Class " + ldom[i] + ": count: " + dist[i] + " prior: " + (float)dist[i]/vecs.numRows());

    // create sampling_ratios for class balance with max. maxrows rows (fill
    // existing array if not null).  Make a defensive copy.
    sampling_ratios = sampling_ratios == null ? new float[dist.length] : sampling_ratios.clone();
    assert sampling_ratios.length == dist.length;
    if( ArrayUtils.minValue(sampling_ratios) == 0 && ArrayUtils.maxValue(sampling_ratios) == 0 ) {
      // compute sampling ratios to achieve class balance
      for (int i=0; i<dist.length;++i)
        sampling_ratios[i] = ((float)vecs.numRows() / ldom.length) / (float)dist[i]; // prior^-1 / num_classes
      final float inv_scale = ArrayUtils.minValue(sampling_ratios); //majority class has lowest required oversampling factor to achieve balance
      if (!Float.isNaN(inv_scale) && !Float.isInfinite(inv_scale))
        ArrayUtils.div(sampling_ratios, inv_scale); //want sampling_ratio 1.0 for majority class (no downsampling)
    }

    if (!allowOversampling)
      for (int i=0; i<sampling_ratios.length; ++i)
        sampling_ratios[i] = Math.min(1.0f, sampling_ratios[i]);

    // given these sampling ratios, and the original class distribution, this is the expected number of resulting rows
    float numrows = 0;
    for (int i=0; i<sampling_ratios.length; ++i) {
      numrows += sampling_ratios[i] * dist[i];
    }
    if (Float.isNaN(numrows)) {
      throw new IllegalArgumentException("Error during sampling - too few points?");
    }

    final long actualnumrows = Math.min(maxrows, Math.round(numrows)); //cap #rows at maxrows
    assert(actualnumrows >= 0); //can have no matching rows in case of sparse data where we had to fill in a makeZero() vector
    Log.info("Stratified sampling to a total of " + String.format("%,d", actualnumrows) + " rows" + (actualnumrows < numrows ? " (limited by max_after_balance_size).":"."));

    if (actualnumrows != numrows) {
      ArrayUtils.mult(sampling_ratios, (float)actualnumrows/numrows); //adjust the sampling_ratios by the global rescaling factor
      if (verbose)
        Log.info("Downsampling majority class by " + (float)actualnumrows/numrows
                + " to limit number of rows to " + String.format("%,d", maxrows));
    }
    for (int i=0;i<ldom.length;++i) {
      Log.info("Class '" + ldom[i] + "' sampling ratio: " + sampling_ratios[i]);
    }

    return sampleFrameStratified(vecs, labelId, weightsId, sampling_ratios, seed, verbose);
  }

  /**
   * Stratified sampling
   * @param vecs Input vecs
   * @param labelId Label vector id (from the input frame)
   * @param weightsId Weight vector id (from the input frame) or -1 if has no weights
   * @param sampling_ratios Given sampling ratios for each class, in order of domains
   * @param seed RNG seed
   * @param debug Whether to print debug info
   * @return Stratified frame
   */
  public static VecAry sampleFrameStratified(final VecAry vecs, int labelId, int weightsId, final float[] sampling_ratios, final long seed, final boolean debug) {
    return sampleFrameStratified(vecs, labelId, weightsId, sampling_ratios, seed, debug, 0);
  }

  // internal version with repeat counter
  // currently hardcoded to do up to 10 tries to get a row from each class, which can be impossible for certain wrong sampling ratios
  private static VecAry sampleFrameStratified(final VecAry vecs, final int labelId, final int weightsId, final float[] sampling_ratios, final long seed, final boolean debug, int count) {
    if (vecs == null) return null;
    String [] ldom = vecs.domain(labelId);
    assert(vecs.isCategorical(labelId));
    assert(sampling_ratios != null && sampling_ratios.length == ldom.length);
//    final int labelidx = fr.find(label); //which column is the label?
//    assert(labelidx >= 0);
//    final int weightsidx = fr.find(weights); //which column is the weight?

    final boolean poisson = false; //beta feature

    //FIXME - this is doing uniform sampling, even if the weights are given
    VecAry r = new MRTask() {
      @Override
      public void map(Chunk[] cs, NewChunk[] ncs) {
        final Random rng = getRNG(seed);
        for (int r = 0; r < cs[0]._len; r++) {
          if (cs[labelId].isNA(r)) continue; //skip missing labels
          rng.setSeed(cs[0].start()+r+seed);
          final int label = (int)cs[labelId].at8(r);
          assert(sampling_ratios.length > label && label >= 0);
          int sampling_reps;
          if (poisson) {
            throw H2O.unimpl();
//            sampling_reps = ArrayUtils.getPoisson(sampling_ratios[label], rng);
          } else {
            final float remainder = sampling_ratios[label] - (int)sampling_ratios[label];
            sampling_reps = (int)sampling_ratios[label] + (rng.nextFloat() < remainder ? 1 : 0);
          }
          for (int i = 0; i < ncs.length; i++) {
            for (int j = 0; j < sampling_reps; ++j) {
              ncs[i].addNum(cs[i].atd(r));
            }
          }
        }
      }
    }.doAll(vecs.types(), vecs).outputVecs(vecs.domains());

    // Confirm the validity of the distribution
    VecAry vs = weightsId != -1 ?vecs.getVecs(labelId,weightsId):vecs.getVecs(labelId);
    double[] dist = new ClassDist(vs).doAll(vs).dist();

    // if there are no training labels in the test set, then there is no point in sampling the test set
    if (dist == null) return vecs;

    if (debug) {
      double sumdist = ArrayUtils.sum(dist);
      Log.info("After stratified sampling: " + sumdist + " rows.");
      for (int i=0; i<dist.length;++i) {
        Log.info("Class " + ldom[i] + ": count: " + dist[i]
                + " sampling ratio: " + sampling_ratios[i] + " actual relative frequency: " + (float)dist[i] / sumdist * dist.length);
      }
    }

    // Re-try if we didn't get at least one example from each class
    if (ArrayUtils.minValue(dist) == 0 && count < 10) {
      Log.info("Re-doing stratified sampling because not all classes were represented (unlucky draw).");
      r.remove();
      return sampleFrameStratified(vecs, labelId, weightsId, sampling_ratios, seed+1, debug, ++count);
    }
    // shuffle intra-chunk
    VecAry shuffled = shuffleVecsPerChunk(r, seed+0x580FF13);
    r.remove();
    return shuffled;
  }

  public static class ReplaceWithCon extends MRTask {
    private final double _conn;

    public ReplaceWithCon(double c) {_conn = c;}

    public void setupLocal(){_vecs.preWriting();}
    @Override public void map(Chunk [] chks) {
      int len = chks[0]._len;
      for(int i = 0;  i < chks.length; ++i)
        chks[i].replaceWith(new C0DChunk(_conn,len));
    }
  }
}
