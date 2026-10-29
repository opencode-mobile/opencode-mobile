package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Apex fixtures: the contract for the C-like lexer in Apex. */
class ApexGoldenTest {
    @Test
    fun classesQueriesAndTriggers() {
        assertGolden(
            "apex",
            """
            public with sharing class Sessions {
                @AuraEnabled(cacheable=true)
                public static String label(Integer count) {
                    return 'sessions: ' + count + '\n';
                }
                PUBLIC STATIC List<Account> find(String name) {
                    List<Account> accs = [SELECT Id, Name, Owner.Email FROM Account
                                          WHERE Name LIKE :name AND CreatedDate = LAST_N_DAYS:30
                                          ORDER BY Name ASC NULLS LAST LIMIT 10];
                    Integer total = [select count() from Contact where AccountId IN :accs];
                    List<List<SObject>> found = [FIND 'Acme*' IN ALL FIELDS RETURNING Account(Name), Contact];
                    insert new Account(Name = 'x');
                    Map<Id, Account> byId = new Map<Id, Account>(accs);
                    if (accs.isEmpty() || accs[0] == null) { throw new AuraHandledException('none'); }
                    return accs;
                }
                public Integer size { get; set; }
            }
            trigger SessionTrigger on Account (before insert, after update) {
                for (Account a : Trigger.new) { a.Name = a.Name.trim(); }
            }
            """.trimIndent(),
            """
            «Keyword|public» «Keyword|with» «Keyword|sharing» «Keyword|class» «Type|Sessions» «Punctuation|{»
                «Attribute|@AuraEnabled»«Punctuation|(»«Variable|cacheable»«Operator|=»«Constant|true»«Punctuation|)»
                «Keyword|public» «Keyword|static» «Keyword|String» «Function|label»«Punctuation|(»«Keyword|Integer» «Variable|count»«Punctuation|)» «Punctuation|{»
                    «Keyword|return» «String|'sessions: '» «Operator|+» «Variable|count» «Operator|+» «String|'»«Constant|\n»«String|'»«Punctuation|;»
                «Punctuation|}»
                «Keyword|PUBLIC» «Keyword|STATIC» «Type|List»«Operator|<»«Type|Account»«Operator|>» «Function|find»«Punctuation|(»«Keyword|String» «Variable|name»«Punctuation|)» «Punctuation|{»
                    «Type|List»«Operator|<»«Type|Account»«Operator|>» «Variable|accs» «Operator|=» «Punctuation|[»«Keyword|SELECT» «Variable|Id»«Punctuation|,» «Variable|Name»«Punctuation|,» «Variable|Owner»«Punctuation|.»«Property|Email» «Keyword|FROM» «Variable|Account»
                                          «Keyword|WHERE» «Variable|Name» «Keyword|LIKE» «Punctuation|:»«Variable|name» «Keyword|AND» «Variable|CreatedDate» «Operator|=» «Constant|LAST_N_DAYS»«Punctuation|:»«Number|30»
                                          «Keyword|ORDER» «Keyword|BY» «Variable|Name» «Keyword|ASC» «Keyword|NULLS» «Keyword|LAST» «Keyword|LIMIT» «Number|10»«Punctuation|];»
                    «Keyword|Integer» «Variable|total» «Operator|=» «Punctuation|[»«Keyword|select» «Function|count»«Punctuation|()» «Keyword|from» «Variable|Contact» «Keyword|where» «Variable|AccountId» «Keyword|IN» «Punctuation|:»«Variable|accs»«Punctuation|];»
                    «Type|List»«Operator|<»«Type|List»«Operator|<»«Type|SObject»«Operator|>>» «Variable|found» «Operator|=» «Punctuation|[»«Keyword|FIND» «String|'Acme*'» «Keyword|IN» «Keyword|ALL» «Keyword|FIELDS» «Keyword|RETURNING» «Function|Account»«Punctuation|(»«Variable|Name»«Punctuation|),» «Variable|Contact»«Punctuation|];»
                    «Keyword|insert» «Keyword|new» «Function|Account»«Punctuation|(»«Type|Name» «Operator|=» «String|'x'»«Punctuation|);»
                    «Type|Map»«Operator|<»«Keyword|Id»«Punctuation|,» «Type|Account»«Operator|>» «Variable|byId» «Operator|=» «Keyword|new» «Type|Map»«Operator|<»«Keyword|Id»«Punctuation|,» «Type|Account»«Operator|>»«Punctuation|(»«Variable|accs»«Punctuation|);»
                    «Keyword|if» «Punctuation|(»«Variable|accs»«Punctuation|.»«Function|isEmpty»«Punctuation|()» «Operator|||» «Variable|accs»«Punctuation|[»«Number|0»«Punctuation|]» «Operator|==» «Constant|null»«Punctuation|)» «Punctuation|{» «Keyword|throw» «Keyword|new» «Function|AuraHandledException»«Punctuation|(»«String|'none'»«Punctuation|);» «Punctuation|}»
                    «Keyword|return» «Variable|accs»«Punctuation|;»
                «Punctuation|}»
                «Keyword|public» «Keyword|Integer» «Variable|size» «Punctuation|{» «Keyword|get»«Punctuation|;» «Keyword|set»«Punctuation|;» «Punctuation|}»
            «Punctuation|}»
            «Keyword|trigger» «Type|SessionTrigger» «Keyword|on» «Type|Account» «Punctuation|(»«Keyword|before» «Keyword|insert»«Punctuation|,» «Keyword|after» «Keyword|update»«Punctuation|)» «Punctuation|{»
                «Keyword|for» «Punctuation|(»«Type|Account» «Variable|a» «Punctuation|:» «Type|Trigger»«Punctuation|.»«Property|new»«Punctuation|)» «Punctuation|{» «Variable|a»«Punctuation|.»«Type|Name» «Operator|=» «Variable|a»«Punctuation|.»«Type|Name»«Punctuation|.»«Function|trim»«Punctuation|();» «Punctuation|}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }
}
