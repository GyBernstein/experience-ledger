package com.example.ledger.authoring;

import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import org.springframework.stereotype.Service;

@Service
public class PromptService {
 private final Db db;
 public PromptService(Db db){this.db=db;}
 public DraftAssistProvider.Prompt current(ActorContext actor,String code){return db.with(actor,null,()->{
  var row=db.one("select code,version,system_prompt,user_template,output_schema from exp_prompt_template where space_id=:space and code=:code and enabled order by version desc limit 1",db.scoped(actor,"code",code));
  return new DraftAssistProvider.Prompt((String)row.get("code"),((Number)row.get("version")).intValue(),(String)row.get("system_prompt"),(String)row.get("user_template"),(tools.jackson.databind.JsonNode)row.get("output_schema"));
 });}
}
