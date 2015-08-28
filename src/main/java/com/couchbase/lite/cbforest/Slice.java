/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 3.0.7
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package com.couchbase.lite.cbforest;

public class Slice {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected Slice(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(Slice obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  protected void finalize() {
    delete();
  }

  public synchronized void delete() {
    if (swigCPtr != 0) {
      if (swigCMemOwn) {
        swigCMemOwn = false;
        cbforestJNI.delete_Slice(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  public Slice() {
    this(cbforestJNI.new_Slice__SWIG_0(), true);
  }

  public Slice(byte[] b) {
    this(cbforestJNI.new_Slice__SWIG_1(b), true);
  }

  public byte[] getBuf() {
    return cbforestJNI.Slice_getBuf(swigCPtr, this);
  }

  public int compare(Slice arg0) {
    return cbforestJNI.Slice_compare(swigCPtr, this, Slice.getCPtr(arg0), arg0);
  }

  public Slice copy() {
    long cPtr = cbforestJNI.Slice_copy(swigCPtr, this);
    return (cPtr == 0) ? null : new Slice(cPtr, false);
  }

  public void free() {
    cbforestJNI.Slice_free(swigCPtr, this);
  }

  public int getSize() {
    return cbforestJNI.Slice_getSize(swigCPtr, this);
  }

  public SWIGTYPE_p_void getData() {
    long cPtr = cbforestJNI.Slice_getData(swigCPtr, this);
    return (cPtr == 0) ? null : new SWIGTYPE_p_void(cPtr, false);
  }

}