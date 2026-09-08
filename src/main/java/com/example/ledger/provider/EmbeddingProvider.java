package com.example.ledger.provider;
public interface EmbeddingProvider {
 record Result(float[] vector,String model) {
  public Result {
   if(vector==null || vector.length!=384 || model==null || model.isBlank())throw new IllegalArgumentException("384 dimensions and a model identifier required");
   double norm=0;for(float f:vector){if(!Float.isFinite(f))throw new IllegalArgumentException("Non-finite embedding");norm+=f*f;}
   if(norm==0)throw new IllegalArgumentException("Zero embedding cannot use cosine distance");vector=vector.clone();
  }
  @Override public float[] vector(){return vector.clone();}
 }
 // null means intentionally disabled, exceptions mean failed.
 Result embed(String text) throws Exception;
 static String literal(Result result){var b=new StringBuilder("[");float[] vector=result.vector();for(int i=0;i<vector.length;i++){if(i>0)b.append(',');b.append(vector[i]);}return b.append(']').toString();}
}
