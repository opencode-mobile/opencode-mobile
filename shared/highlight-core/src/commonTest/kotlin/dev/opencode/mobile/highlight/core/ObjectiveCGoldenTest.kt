package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Objective-C fixtures: the contract for the C-like lexer in Objective-C. */
class ObjectiveCGoldenTest {
    @Test
    fun interfaceImplementationAndMessages() {
        assertGolden(
            "objective-c",
            """
            #import <Foundation/Foundation.h>
            #import "Session.h"

            NS_ASSUME_NONNULL_BEGIN

            @interface Session : NSObject <NSCopying>
            @property (nonatomic, copy, nullable) NSString *name;
            @property (nonatomic, assign) BOOL active;
            - (instancetype)initWithName:(NSString *)name count:(NSInteger)count;
            + (void)reset;
            @end

            @implementation Session
            @synthesize name = _name;

            - (instancetype)initWithName:(NSString *)name count:(NSInteger)count {
                self = [super init];
                if (self) {
                    _name = [name copy];
                    NSArray *items = @[@"a", @42, @YES, @(count)];
                    NSDictionary *map = @{@"key": @3.5};
                    NSLog(@"Session %@ has %ld items\n", name, (long)count);
                    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(reset) name:nil object:nil];
                    id obj = [NSString stringWithFormat:@"%d", 3];
                    BOOL ok = flag ? YES : NO;
                }
                return self;
            }

            - (void)dealloc {
                [self.timer invalidate];
            }
            @end

            NSString *Label(NSInteger count) {
                return [NSString stringWithFormat:@"sessions: %ld", (long)count];
            }
            """.trimIndent(),
            """
            «Punctuation|#»«Keyword|import» «String|<Foundation/Foundation.h>»
            «Punctuation|#»«Keyword|import» «String|"Session.h"»

            «Constant|NS_ASSUME_NONNULL_BEGIN»

            «Keyword|@interface» «Type|Session» «Punctuation|:» «Type|NSObject» «Operator|<»«Type|NSCopying»«Operator|>»
            «Keyword|@property» «Punctuation|(»«Keyword|nonatomic»«Punctuation|,» copy«Punctuation|,» «Keyword|nullable»«Punctuation|)» «Type|NSString» «Operator|*»name«Punctuation|;»
            «Keyword|@property» «Punctuation|(»«Keyword|nonatomic»«Punctuation|,» assign«Punctuation|)» «Keyword|BOOL» active«Punctuation|;»
            «Operator|-» «Punctuation|(»«Keyword|instancetype»«Punctuation|)»«Function|initWithName»«Punctuation|:(»«Type|NSString» «Operator|*»«Punctuation|)»name «Function|count»«Punctuation|:(»«Type|NSInteger»«Punctuation|)»count«Punctuation|;»
            «Operator|+» «Punctuation|(»«Keyword|void»«Punctuation|)»«Function|reset»«Punctuation|;»
            «Keyword|@end»

            «Keyword|@implementation» «Type|Session»
            «Keyword|@synthesize» name «Operator|=» _name«Punctuation|;»

            «Operator|-» «Punctuation|(»«Keyword|instancetype»«Punctuation|)»«Function|initWithName»«Punctuation|:(»«Type|NSString» «Operator|*»«Punctuation|)»name «Function|count»«Punctuation|:(»«Type|NSInteger»«Punctuation|)»count «Punctuation|{»
                «Keyword|self» «Operator|=» «Punctuation|[»«Keyword|super» «Function|init»«Punctuation|];»
                «Keyword|if» «Punctuation|(»«Keyword|self»«Punctuation|)» «Punctuation|{»
                    _name «Operator|=» «Punctuation|[»name «Function|copy»«Punctuation|];»
                    «Type|NSArray» «Operator|*»items «Operator|=» «Punctuation|@[»«String|@"a"»«Punctuation|,» «Number|@42»«Punctuation|,» «Constant|@YES»«Punctuation|,» «Punctuation|@(»count«Punctuation|)];»
                    «Type|NSDictionary» «Operator|*»map «Operator|=» «Punctuation|@{»«String|@"key"»«Punctuation|:» «Number|@3.5»«Punctuation|};»
                    «Function|NSLog»«Punctuation|(»«String|@"Session »«Constant|%@»«String| has »«Constant|%ld»«String| items»«Constant|\n»«String|"»«Punctuation|,» name«Punctuation|,» «Punctuation|(»«Keyword|long»«Punctuation|)»count«Punctuation|);»
                    «Punctuation|[[»«Type|NSNotificationCenter» «Function|defaultCenter»«Punctuation|]» «Function|addObserver»«Punctuation|:»«Keyword|self» «Function|selector»«Punctuation|:»«Keyword|@selector»«Punctuation|(»reset«Punctuation|)» «Function|name»«Punctuation|:»«Constant|nil» «Function|object»«Punctuation|:»«Constant|nil»«Punctuation|];»
                    «Keyword|id» obj «Operator|=» «Punctuation|[»«Type|NSString» «Function|stringWithFormat»«Punctuation|:»«String|@"»«Constant|%d»«String|"»«Punctuation|,» «Number|3»«Punctuation|];»
                    «Keyword|BOOL» ok «Operator|=» flag «Operator|?» «Constant|YES» «Punctuation|:» «Constant|NO»«Punctuation|;»
                «Punctuation|}»
                «Keyword|return» «Keyword|self»«Punctuation|;»
            «Punctuation|}»

            «Operator|-» «Punctuation|(»«Keyword|void»«Punctuation|)»«Function|dealloc» «Punctuation|{»
                «Punctuation|[»«Keyword|self»«Punctuation|.»«Property|timer» «Function|invalidate»«Punctuation|];»
            «Punctuation|}»
            «Keyword|@end»

            «Type|NSString» «Operator|*»«Function|Label»«Punctuation|(»«Type|NSInteger» count«Punctuation|)» «Punctuation|{»
                «Keyword|return» «Punctuation|[»«Type|NSString» «Function|stringWithFormat»«Punctuation|:»«String|@"sessions: »«Constant|%ld»«String|"»«Punctuation|,» «Punctuation|(»«Keyword|long»«Punctuation|)»count«Punctuation|];»
            «Punctuation|}»
            """.trimIndent(),
        )
    }
}
