/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 3.0.7
 *
 * This file is not intended to be easily readable and contains a number of
 * coding conventions designed to improve portability and efficiency. Do not make
 * changes to this file unless you know what you are doing--modify the SWIG
 * interface file instead.
 * ----------------------------------------------------------------------------- */

#ifndef SWIG_cbforest_WRAP_H_
#define SWIG_cbforest_WRAP_H_

class SwigDirector_MapFn : public CBF::MapFn, public Swig::Director {

public:
    void swig_connect_director(JNIEnv *jenv, jobject jself, jclass jcls, bool swig_mem_own, bool weak_global);
    SwigDirector_MapFn(JNIEnv *jenv);
    virtual ~SwigDirector_MapFn();
    virtual void call(CBF::Mappable const &arg0, CBF::EmitFn &emit);
    virtual void operator ()(forestdb::Mappable const &arg0, forestdb::EmitFn &emit);
public:
    bool swig_overrides(int n) {
      return (n < 1 ? swig_override[n] : false);
    }
protected:
    Swig::BoolArray<1> swig_override;
};

class SwigDirector_MapReduceIndexer : public CBF::MapReduceIndexer, public Swig::Director {

public:
    void swig_connect_director(JNIEnv *jenv, jobject jself, jclass jcls, bool swig_mem_own, bool weak_global);
    SwigDirector_MapReduceIndexer(JNIEnv *jenv);
    virtual ~SwigDirector_MapReduceIndexer();
    virtual void bridgeAddDocument(forestdb::Document const &arg0);
    virtual void addDocument(CBF::Document const *arg0);
public:
    bool swig_overrides(int n) {
      return (n < 1 ? swig_override[n] : false);
    }
protected:
    Swig::BoolArray<1> swig_override;
};


#endif
