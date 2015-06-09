package org.acl.deepspark.nn.layers.cnn;

import org.acl.deepspark.nn.layers.BaseLayer;
import org.acl.deepspark.nn.weights.WeightUtil;
import org.jblas.DoubleMatrix;
import org.jblas.SimpleBlas;
import org.jblas.ranges.RangeUtils;

public class ConvolutionLayer extends BaseLayer {
	private int filterRows, filterCols, numFilters; // filter spec.
	private DoubleMatrix[][] W; // filterId, x, y
	private double[] bias;
	
	// momentum 
	private double momentumFactor = 0.95;
	private DoubleMatrix[][] prevDeltaW;
	private double[] prevDeltaBias;
		
	// weight decay
	private double decayLambda = 1e-5;
	
	private int[] stride = {1, 1};
	private int zeroPadding = 0;
	private boolean useZeroPadding = true;

	public ConvolutionLayer(int filterRows, int filterCols, int numFilters) {
		super();
		this.filterRows = filterRows;
		this.filterCols = filterCols;
		this.numFilters = numFilters;
	}
	
	public ConvolutionLayer(DoubleMatrix input, int filterRows, int filterCols, int numFilters) {
		super(input);
		this.filterRows = filterRows;
		this.filterCols = filterCols;
		this.numFilters = numFilters;
		initWeights();
	}
	
	public ConvolutionLayer(DoubleMatrix[] input, int filterRows, int filterCols, int numFilters) {
		super(input);
		this.filterRows = filterRows;
		this.filterCols = filterCols;
		this.numFilters = numFilters;
		initWeights();
	}
	
	public ConvolutionLayer(DoubleMatrix input, int filterRows, int filterCols, int numFilters, double momentum) {
		this(input, filterRows, filterCols, numFilters);
		momentumFactor = momentum;
	}
	
	public ConvolutionLayer(DoubleMatrix[] input, int filterRows, int filterCols, int numFilters,double momentum) {
		this(input, filterRows, filterCols, numFilters);
		momentumFactor = momentum;
	}
	
	public void setFilterWeights(DoubleMatrix[][] filters) {
		W = filters;
	}
	
	public DoubleMatrix[][] getFilterWeights() {
		return W;
	}
	
	@Override
	public void initWeights() {
		if (W == null || bias == null) {
			W = new DoubleMatrix[numFilters][numChannels];
			prevDeltaW = new DoubleMatrix[numFilters][numChannels];
			
			bias = new double[numFilters];
			prevDeltaBias = new double[numFilters];
			
			for(int i = 0; i < numFilters; i++) {
				for(int j = 0; j < numChannels; j++) {
					W[i][j] = WeightUtil.randInitWeights(filterRows, filterCols);
					prevDeltaW[i][j] = DoubleMatrix.zeros(filterRows, filterCols);
				}
				bias[i] = 0.01;
				prevDeltaBias[i] = 0;
			}
		}
	}

	public int getNumOfChannels() {
		return numChannels;
	}
	
	public int getNumOfFilter() {
		return numFilters;
	}
	
	private int getOutputRows() {
		return  dimRows - filterRows + 1;
	}
	
	private int getOutputCols() {
		return  dimCols - filterCols + 1;
	}
	
	// Convolution of multiple channel input images
	public DoubleMatrix[] convolution() {
		DoubleMatrix[] data = new DoubleMatrix[numFilters];
		DoubleMatrix filter;
		DoubleMatrix temp = new DoubleMatrix(getOutputRows(), getOutputCols());
		
		// check: dims(image) > dims(filter)
		for(int i = 0; i < numFilters; i++) {
			data[i] = new DoubleMatrix(getOutputRows(), getOutputCols());
			for(int j = 0; j < numChannels; j++) {
				filter = new DoubleMatrix(W[i][j].toArray2());
				temp.fill(0.0);
				// calculate convolutions
				for(int r = 0; r < temp.rows; r++) {
					for(int c = 0; c < temp.columns ; c++) {
						temp.put(r, c, SimpleBlas.dot
								(input[j].get(RangeUtils.interval(r, r + filter.rows),
										   	  RangeUtils.interval(c, c + filter.columns)), filter));
					}
				}
				data[i].addi(temp);
			}
			data[i].addi(bias[i]);
		}
		return data;
	}
	
	@Override
	public DoubleMatrix[] getOutput() {
		output = activate(convolution());
		return output;
	}

	@Override
	public DoubleMatrix[] update(DoubleMatrix[] propDelta) {
		for(int i = 0 ; i < propDelta.length; i++)
			propDelta[i].muli(output[i].mul(output[i].mul(-1.0).add(1.0)));
		
		DoubleMatrix deltaWeight = new DoubleMatrix(filterRows, filterCols);
		// update Weights
		for (int i = 0; i < numFilters; i++) {
			for (int j = 0; j < numChannels; j++) {
				deltaWeight.fill(0.0);
				for(int r = 0; r < filterRows; r++) {
					for(int c = 0; c < filterCols ; c++) {
						deltaWeight.put(r, c, SimpleBlas.dot
								(input[j].get(RangeUtils.interval(r, r + propDelta[i].rows),
										   	  RangeUtils.interval(c, c + propDelta[i].columns)), propDelta[i]));
					}
				}
				//W[i][j].subi(W[i][j].mul(0.00001).mul(learningRate));
				
				prevDeltaW[i][j].muli(momentumFactor);
				prevDeltaW[i][j].addi(deltaWeight.mul(learningRate));
				prevDeltaW[i][j].addi(W[i][j].mul(learningRate * decayLambda));
				
				prevDeltaBias[i] = propDelta[i].sum() * learningRate + prevDeltaBias[i] * momentumFactor;
				
				W[i][j].subi(prevDeltaW[i][j]);
				bias[i] -= prevDeltaBias[i];
			}
		}
		// propagate delta to previous layer
		return deriveDelta (propDelta);
	}
	
	public DoubleMatrix[] deriveDelta(DoubleMatrix[] propDelta) {
		if (propDelta == null || propDelta.length <= 0)
			return null;
		
		DoubleMatrix[] delta = new DoubleMatrix[numChannels];
		DoubleMatrix temp = new DoubleMatrix(dimRows, dimCols);
		DoubleMatrix filter;
		
		// check: dims(image) > dims(filter)
		int conv;
		for (int j = 0; j < numChannels; j++) {
			delta[j] = new DoubleMatrix(dimRows, dimCols);
			for (int i = 0; i < numFilters; i++) {
				filter = new DoubleMatrix(W[i][j].toArray2());
				
				temp.fill(0.0);
				// calculate convolution
				for (int r = 0; r < dimRows; r++) {
					for (int c = 0; c < dimCols ; c++) {
						conv = 0;
						for (int m = 0; m < filterRows; m++) {
							for (int n = 0; n < filterCols; n++) {
								if (r-m < 0 || r-m >= getOutputRows() || c-n < 0 || c-n >= getOutputCols())
									continue;
								conv += propDelta[i].get(r-m, c-n) * filter.get(m,n);
							}
						}
						temp.put(r, c, conv);
					}
				}
				delta[j].addi(temp);
			}
		}
		return delta;
	}

	@Override
	public void applyDropOut() {
		// TODO Auto-generated method stub
		
	}
}
