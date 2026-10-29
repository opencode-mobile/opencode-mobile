package dev.opencode.mobile.highlight.bench

/**
 * Typical code for the Full milestone's 38 languages (#872), repeated by [HighlightBenchCorpus.code]
 * with `{n}` replaced by the copy's index. Each is one realistic file of about 1-2.5K characters
 * that exercises the language's own constructs: column rules in COBOL and fixed-form Fortran,
 * nested comments in Haskell, OCaml, F#, Julia, Nim, and Common Lisp, heredocs and here-strings
 * in Perl, PowerShell, Crystal, and Elixir, long strings in Lua, interpolation in most of them,
 * tab-indented recipes in Make, and `<template>`, `<script>`, and `<style>` blocks in Vue and Svelte.
 */
internal object HighlightBenchCorpusFullTemplates {
    val full: Map<String, String> =
        mapOf(
            "abap" to
                """
                |*&---------------------------------------------------------------------*
                |*& Report ZSESSION_REPORT_{n}: lists sessions and their token usage.
                |*&---------------------------------------------------------------------*
                |REPORT zsession_report_{n}.
                |
                |TYPES: BEGIN OF ty_session,
                |         id     TYPE string,
                |         title  TYPE string,
                |         tokens TYPE i,
                |         cost   TYPE p LENGTH 10 DECIMALS 2,
                |       END OF ty_session,
                |       tt_sessions TYPE STANDARD TABLE OF ty_session WITH DEFAULT KEY.
                |
                |CONSTANTS gc_limit_{n} TYPE i VALUE 64.
                |
                |CLASS lcl_store DEFINITION FINAL.
                |  PUBLIC SECTION.
                |    METHODS load
                |      IMPORTING iv_id             TYPE string
                |      RETURNING VALUE(rs_session) TYPE ty_session
                |      RAISING   cx_sy_itab_line_not_found.
                |  PRIVATE SECTION.
                |    DATA mt_sessions TYPE tt_sessions.
                |ENDCLASS.
                |
                |CLASS lcl_store IMPLEMENTATION.
                |  METHOD load.
                |    " Read by key; raises when the session is missing.
                |    rs_session = mt_sessions[ id = iv_id ].
                |  ENDMETHOD.
                |ENDCLASS.
                |
                |START-OF-SELECTION.
                |  DATA(lo_store) = NEW lcl_store( ).
                |  DATA(lt_sessions) = VALUE tt_sessions(
                |    ( id = 'ses_1' title = `First` tokens = 120 cost = '0.25' )
                |    ( id = 'ses_2' title = |Second { sy-datum DATE = ISO }| tokens = 3400 ) ).
                |
                |  LOOP AT lt_sessions INTO DATA(ls_session) WHERE tokens > 100.
                |    IF ls_session-tokens >= gc_limit_{n} AND ls_session-cost IS INITIAL.
                |      WRITE: / ls_session-id, ls_session-title.
                |    ELSEIF ls_session-title CS 'draft'.
                |      CONTINUE.
                |    ELSE.
                |      WRITE: / |{ ls_session-id }: { ls_session-tokens WIDTH = 8 ALIGN = RIGHT }|.
                |    ENDIF.
                |  ENDLOOP.
                |
                |  TRY.
                |      DATA(ls_found) = lo_store->load( iv_id = 'ses_404' ).
                |    CATCH cx_sy_itab_line_not_found INTO DATA(lx_error).
                |      MESSAGE lx_error->get_text( ) TYPE 'I'.
                |  ENDTRY.
                |
                |  SELECT carrid, connid, fldate
                |    FROM sflight
                |    WHERE price > @( 100 * 2 )
                |    INTO TABLE @DATA(lt_flights)
                |    UP TO 10 ROWS.
                |
                """.trimMargin(),
            "ada" to
                """
                |--  Session store {n}: keeps the latest revision per key.
                |with Ada.Text_IO;            use Ada.Text_IO;
                |with Ada.Containers.Hashed_Maps;
                |with Ada.Strings.Unbounded;  use Ada.Strings.Unbounded;
                |
                |package body Session_Store_{n} is
                |
                |   type Status is (Idle, Busy, Failed);
                |   subtype Capacity_Range is Positive range 1 .. 1_024;
                |
                |   Max_Items : constant Capacity_Range := 64;
                |   Mask      : constant := 16#1F#;
                |   Ratio     : constant Float := 0.75E+0;
                |
                |   type Session is record
                |      Id     : Unbounded_String;
                |      Tokens : Natural := 0;
                |      State  : Status  := Idle;
                |   end record;
                |
                |   package Session_Maps is new Ada.Containers.Hashed_Maps
                |     (Key_Type        => Unbounded_String,
                |      Element_Type    => Session,
                |      Hash            => Ada.Strings.Unbounded.Hash,
                |      Equivalent_Keys => "=");
                |
                |   Items : Session_Maps.Map;
                |   Not_Found_{n} : exception;
                |
                |   function Load (Id : String) return Session is
                |      Key : constant Unbounded_String := To_Unbounded_String (Id);
                |   begin
                |      if not Items.Contains (Key) then
                |         raise Not_Found_{n} with "missing session ""${'"'} & Id & ""${'"'}";
                |      end if;
                |      return Items.Element (Key);
                |   end Load;
                |
                |   procedure Store (Item : in out Session; Tokens : Natural) is
                |   begin
                |      Item.Tokens := Item.Tokens + Tokens;
                |      Item.State := (if Tokens > Max_Items then Busy else Idle);
                |      Items.Include (Item.Id, Item);
                |      for C in Items.Iterate loop
                |         exit when Natural (Items.Length) <= Max_Items;
                |         Put_Line ("evicting " & To_String (Session_Maps.Key (C)) & '.');
                |      end loop;
                |   exception
                |      when Constraint_Error =>
                |         Put_Line (Standard_Error, "overflow at" & Natural'Image (Tokens));
                |   end Store;
                |
                |   task type Worker_{n} (Count : Positive) is
                |      entry Start;
                |   end Worker_{n};
                |
                |   task body Worker_{n} is
                |   begin
                |      accept Start;
                |      for I in reverse 1 .. Count loop
                |         delay 0.5;
                |      end loop;
                |   end Worker_{n};
                |
                |end Session_Store_{n};
                |
                """.trimMargin(),
            "apex" to
                """
                |/**
                | * Session service {n}: syncs sessions with the opencode server.
                | */
                |public with sharing class SessionService{n} implements Queueable, Database.AllowsCallouts {
                |    private static final Integer MAX_RETRIES = 3;
                |    private static final String ENDPOINT = 'callout:Opencode/session/';
                |    @TestVisible private final List<Id> accountIds;
                |
                |    public enum Status { IDLE, BUSY, FAILED }
                |
                |    public SessionService{n}(List<Id> accountIds) {
                |        this.accountIds = accountIds;
                |    }
                |
                |    public void execute(QueueableContext context) {
                |        Map<Id, Account> accounts = new Map<Id, Account>([
                |            SELECT Id, Name, (SELECT Id, Subject__c FROM Sessions__r WHERE CreatedDate = LAST_N_DAYS:30)
                |            FROM Account
                |            WHERE Id IN :accountIds AND Active__c = true
                |            ORDER BY Name ASC NULLS LAST
                |            LIMIT 200
                |        ]);
                |        List<Session__c> updates = new List<Session__c>();
                |        for (Account acc : accounts.values()) {
                |            HttpRequest request = new HttpRequest();
                |            request.setEndpoint(ENDPOINT + EncodingUtil.urlEncode(acc.Name, 'UTF-8'));
                |            request.setMethod('GET');
                |            request.setTimeout(12000);
                |            HttpResponse response = new Http().send(request);
                |            if (response.getStatusCode() != 200) {
                |                System.debug(LoggingLevel.WARN, 'load failed: ' + response.getStatus());
                |                continue;
                |            }
                |            Map<String, Object> body = (Map<String, Object>) JSON.deserializeUntyped(response.getBody());
                |            updates.add(new Session__c(Account__c = acc.Id, Tokens__c = (Decimal) body.get('tokens') * 1.5));
                |        }
                |        try {
                |            Database.upsert(updates, Session__c.External_Id__c, false);
                |        } catch (DmlException e) {
                |            insert new Log__c(Message__c = e.getMessage().left(255), Code__c = 31L);
                |        }
                |    }
                |
                |    @AuraEnabled(cacheable=true)
                |    public static List<Session__c> recent{n}(String accountId) {
                |        return [SELECT Id, Name FROM Session__c WHERE Account__c = :accountId WITH SECURITY_ENFORCED];
                |    }
                |}
                |
                |trigger SessionTrigger{n} on Session__c (before insert, after update) {
                |    if (Trigger.isBefore) {
                |        for (Session__c s : Trigger.new) {
                |            s.Status__c = SessionService{n}.Status.IDLE.name();
                |        }
                |    }
                |}
                |
                """.trimMargin(),
            "asm" to
                """
                |; session_{n}.asm: sums token counts for a session (x86-64, NASM, System V ABI)
                |        bits    64
                |        default rel
                |
                |%define SYS_WRITE   1
                |%define SYS_EXIT    60
                |%define MAX_ITEMS   64
                |
                |%macro PRINT 2
                |        mov     eax, SYS_WRITE
                |        mov     edi, 1
                |        lea     rsi, [%1]
                |        mov     edx, %2
                |        syscall
                |%endmacro
                |
                |        section .data
                |title_{n}:      db      "session {n}: ", 0
                |title_{n}_len   equ     ${'$'} - title_{n}
                |tokens_{n}:     dd      120, 3400, 0x1F, 0b1010, 0o17, -42
                |                times   MAX_ITEMS - 6 dd 0
                |ratio_{n}:      dq      0.75
                |newline_{n}:    db      10
                |
                |        section .bss
                |buffer_{n}:     resb    32
                |
                |        section .text
                |        global  sum_tokens_{n}
                |; rdi = pointer to a dword array, esi = count. Returns the sum in rax.
                |sum_tokens_{n}:
                |        push    rbp
                |        mov     rbp, rsp
                |        xor     eax, eax                ; running total
                |        xor     ecx, ecx
                |        test    esi, esi
                |        jz      .done
                |.loop:
                |        movsxd  rdx, dword [rdi + rcx*4]
                |        add     rax, rdx
                |        inc     ecx
                |        cmp     ecx, esi
                |        jb      .loop
                |.done:
                |        pop     rbp
                |        ret
                |
                |        global  start_{n}
                |start_{n}:
                |        lea     rdi, [tokens_{n}]
                |        mov     esi, MAX_ITEMS
                |        call    sum_tokens_{n}
                |        PRINT   title_{n}, title_{n}_len
                |        cmp     rax, 'A'
                |        jae     .big
                |        PRINT   newline_{n}, 1
                |.big:
                |        mov     eax, SYS_EXIT
                |        xor     edi, edi
                |        syscall
                |
                """.trimMargin(),
            "awk" to
                """
                |#!/usr/bin/awk -f
                |# Session report {n}: sums tokens per session from a CSV log.
                |BEGIN {
                |    FS = ","
                |    OFS = "\t"
                |    limit_{n} = 1e3
                |    pattern = "^ses_[a-z0-9]+${'$'}"
                |    printf "%-20s %8s %s\n", "session", "tokens", "status" > "/dev/stderr"
                |}
                |
                |# Skip comments and the header.
                |/^#/ || NR == 1 { next }
                |
                |${'$'}1 ~ pattern && ${'$'}3 != "" {
                |    tokens[${'$'}1] += ${'$'}3
                |    count[${'$'}1]++
                |    if (${'$'}4 ~ /error|fail(ed)?/) errors[${'$'}1] = errors[${'$'}1] " " NR
                |}
                |
                |${'$'}3 > limit_{n} {
                |    big++
                |    sub(/^ses_/, "", ${'$'}1)
                |    print "large", ${'$'}1, ${'$'}3 * 1.5 | "sort -k2 -n"
                |}
                |
                |function percent_{n}(part, total,    result) {
                |    if (total == 0) return 0
                |    result = sprintf("%.1f", part / total * 100)
                |    return result + 0
                |}
                |
                |END {
                |    for (id in tokens) {
                |        total += tokens[id]
                |    }
                |    n = asorti(tokens, ids)
                |    for (i = 1; i <= n; i++) {
                |        id = ids[i]
                |        status = (id in errors) ? "errors at" errors[id] : "ok"
                |        printf "%-20s %8d %5.1f%% %s\n", id, tokens[id], percent_{n}(tokens[id], total), status
                |    }
                |    close("sort -k2 -n")
                |    while ((getline line < "summary.txt") > 0) lines++
                |    print "large:", big + 0, "lines:", lines, "hex:", 0x1F
                |    exit (big > 0 ? 1 : 0)
                |}
                |
                """.trimMargin(),
            "clojure" to
                """
                |(ns opencode.session-store-{n}
                |  "Session store {n}: keeps the latest revision per key."
                |  (:require [clojure.string :as str]
                |            [clojure.core.async :as async :refer [go <! >! chan]])
                |  (:import (java.time Instant)))
                |
                |(def ^:const max-items 64)
                |(def ^:private id-pattern #"^ses_[a-z0-9]+${'$'}")
                |
                |(defrecord Session [id title tokens status])
                |
                |(defprotocol SessionLoader
                |  (load-session [this id] "Loads a session by id, or nil."))
                |
                |(defonce store-{n} (atom {:items {} :revision 0}))
                |
                |(defn- evict
                |  "Drops the oldest items above `max-items`."
                |  [items]
                |  (if (> (count items) max-items)
                |    (into {} (drop (- (count items) max-items)) items)
                |    items))
                |
                |(defn put-session!
                |  [{:keys [id tokens] :or {tokens 0} :as session}]
                |  {:pre [(re-matches id-pattern id)]}
                |  (swap! store-{n}
                |         (fn [state]
                |           (-> state
                |               (update :items assoc id (map->Session session))
                |               (update :items evict)
                |               (update :revision inc)))))
                |
                |(defmulti describe :status)
                |(defmethod describe :busy [_] (str "busy since " (Instant/now)))
                |(defmethod describe :default [{:keys [title tokens]}]
                |  (format "%s (%d tokens, %.2f ratio)" title tokens 0.75))
                |
                |(defn summary-{n} []
                |  (let [{:keys [items revision]} @store-{n}
                |        total (transduce (map :tokens) + 0 (vals items))]
                |    (cond
                |      (zero? total) :empty
                |      (> total 1e6) {:total total :large? true :revision revision}
                |      :else (->> (vals items)
                |                 (filter #(str/starts-with? (:id %) "ses_"))
                |                 (map describe)
                |                 (str/join \newline)))))
                |
                |(comment
                |  (put-session! {:id "ses_1" :title "First" :tokens 0x1F :status :idle})
                |  (summary-{n}))
                |
                |#_(this form is ignored by the reader)
                |(let [c (chan 8)]
                |  (go (>! c ^long (+ 1 2N 3/4)))
                |  (go (println (<! c) \a \space 'sym nil true)))
                |
                """.trimMargin(),
            "cobol" to
                """
                |      ******************************************************************
                |      * SESSION-REPORT-{n}: SUMS TOKEN COUNTS PER SESSION.
                |      ******************************************************************
                |       IDENTIFICATION DIVISION.
                |       PROGRAM-ID. SESSRPT{n}.
                |       AUTHOR. PLATFORM-TEAM.
                |       ENVIRONMENT DIVISION.
                |       INPUT-OUTPUT SECTION.
                |       FILE-CONTROL.
                |           SELECT SESSION-FILE ASSIGN TO "SESSIONS.DAT"
                |               ORGANIZATION IS LINE SEQUENTIAL
                |               FILE STATUS IS WS-FILE-STATUS.
                |       DATA DIVISION.
                |       FILE SECTION.
                |       FD  SESSION-FILE.
                |       01  SESSION-RECORD.
                |           05  SR-ID               PIC X(16).
                |           05  SR-TOKENS           PIC 9(7).
                |           05  SR-COST             PIC 9(5)V99.
                |       WORKING-STORAGE SECTION.
                |       01  WS-FILE-STATUS          PIC XX VALUE "00".
                |           88  WS-EOF              VALUE "10".
                |       01  WS-TOTAL-{n}            PIC 9(9) COMP-3 VALUE ZERO.
                |       01  WS-COUNT                PIC 9(5) VALUE 0.
                |       01  WS-AVERAGE              PIC Z(6)9.99.
                |       01  WS-MESSAGE              PIC X(60) VALUE 'SESSION REPORT FOR
                |      -    'ALL ACTIVE ACCOUNTS'.
                |       PROCEDURE DIVISION.
                |       MAIN-PARA.
                |           OPEN INPUT SESSION-FILE
                |           PERFORM READ-PARA UNTIL WS-EOF
                |           IF WS-COUNT > 0
                |               COMPUTE WS-AVERAGE ROUNDED = WS-TOTAL-{n} / WS-COUNT
                |               DISPLAY "AVERAGE TOKENS: " WS-AVERAGE
                |           ELSE
                |               DISPLAY "NO SESSIONS FOUND"
                |           END-IF
                |           CLOSE SESSION-FILE
                |           STOP RUN.
                |       READ-PARA.
                |           READ SESSION-FILE
                |               AT END SET WS-EOF TO TRUE
                |               NOT AT END
                |                   ADD SR-TOKENS TO WS-TOTAL-{n}
                |                   ADD 1 TO WS-COUNT
                |                   EVALUATE TRUE
                |                       WHEN SR-TOKENS > 1000
                |                           DISPLAY SR-ID " IS LARGE"
                |                       WHEN SR-COST = ZERO
                |                           CONTINUE
                |                       WHEN OTHER
                |                           MOVE SPACES TO WS-MESSAGE
                |                   END-EVALUATE
                |           END-READ.
                |
                """.trimMargin(),
            "coffee" to
                """
                |###
                |Session store {n}: keeps the latest revision per key.
                |###
                |{ EventEmitter } = require 'events'
                |
                |ID_PATTERN_{n} = /^ses_[a-z0-9]+${'$'}/i
                |DEFAULTS = retries: 3, timeoutMs: 1500, ratio: 0.75
                |
                |class SessionStore{n} extends EventEmitter
                |  @instances: 0
                |
                |  constructor: (@capacity = 64, options = {}) ->
                |    super()
                |    @items = new Map
                |    @options = { DEFAULTS..., options... }
                |    SessionStore{n}.instances += 1
                |
                |  load: (id) =>
                |    throw new TypeError "bad id: #{id}" unless ID_PATTERN_{n}.test id
                |    response = await fetch "/session/#{encodeURIComponent id}?limit=#{@capacity * 2}"
                |    return null unless response.ok
                |    body = await response.json()
                |    @items.set id, body.session
                |    @evict() if @items.size > @capacity
                |    body.session ? null
                |
                |  evict: ->
                |    for key from @items.keys()
                |      break if @items.size <= @capacity
                |      @items.delete key
                |    @emit 'evicted', @items.size
                |
                |  summary: ->
                |    tokens = (s.tokens for own id, s of Object.fromEntries(@items) when s.tokens > 0)
                |    ""${'"'}
                |    Store #{@constructor.name}
                |      items: #{@items.size}
                |      tokens: #{tokens.reduce ((a, b) -> a + b), 0}
                |    ""${'"'}
                |
                |describe = (status) ->
                |  switch status
                |    when 'idle', 'done' then 'resting'
                |    when 'busy' then "working since #{new Date().toISOString()}"
                |    else 'unknown'
                |
                |squares = (x ** 2 for x in [1..10] by 2)
                |hex = 0x1F
                |pattern = ///
                |  ^ ses_        # prefix
                |  [a-z0-9]+ ${'$'}   # id
                |///i
                |module.exports = { SessionStore{n}, describe, squares }
                |
                """.trimMargin(),
            "common-lisp" to
                """
                |;;;; session-store-{n}.lisp: keeps the latest revision per key.
                |(defpackage #:session-store-{n}
                |  (:use #:cl)
                |  (:export #:make-store #:load-session #:store-size))
                |(in-package #:session-store-{n})
                |
                |(defparameter *max-items* 64
                |  "How many sessions a store keeps before evicting.")
                |(defconstant +mask+ #x1F)
                |
                |(defstruct (session (:constructor make-session (id title &key (tokens 0) (status :idle))))
                |  (id "" :type string)
                |  title
                |  (tokens 0 :type fixnum)
                |  status)
                |
                |(defclass store ()
                |  ((items :initform (make-hash-table :test #'equal) :accessor store-items)
                |   (revision :initform 0 :accessor store-revision))
                |  (:documentation "A bounded session store."))
                |
                |(define-condition session-not-found (error)
                |  ((id :initarg :id :reader missing-id))
                |  (:report (lambda (c stream)
                |             (format stream "Session ~S not found" (missing-id c)))))
                |
                |(defgeneric load-session (store id)
                |  (:method ((store store) (id string))
                |    (or (gethash id (store-items store))
                |        (error 'session-not-found :id id))))
                |
                |(defun store-size (store &optional (predicate #'identity))
                |  (loop for session being the hash-values of (store-items store)
                |        when (funcall predicate session)
                |          count it into total
                |        finally (return (+ total 1000))))
                |
                |(defmacro with-session ((var store id) &body body)
                |  `(let ((,var (load-session ,store ,id)))
                |     (when ,var ,@body)))
                |
                |#|
                |  Block comments #| nest |# in Common Lisp.
                ||#
                |(defun demo-{n} ()
                |  (let ((store (make-instance 'store)))
                |    (setf (gethash "ses_1" (store-items store)) (make-session "ses_1" "First" :tokens 3))
                |    (handler-case (with-session (s store "ses_1")
                |                    (format t "~&~A: ~D tokens, ~,2F~%" (session-title s) (session-tokens s) 0.75d0))
                |      (session-not-found (e) (princ e)))
                |    (list (mapcar #'1+ '(1 2 3)) #\a #\Space 3/4 nil t)))
                |
                """.trimMargin(),
            "crystal" to
                """
                |# Session store {n}: keeps the latest revision per key.
                |require "http/client"
                |require "json"
                |
                |module Opencode
                |  enum Status{n}
                |    Idle
                |    Busy
                |    Failed
                |  end
                |
                |  struct Session
                |    include JSON::Serializable
                |
                |    getter id : String
                |    getter title : String
                |    @[JSON::Field(key: "tokens")]
                |    getter tokens : Int64 = 0_i64
                |
                |    def initialize(@id, @title, @tokens = 0_i64)
                |    end
                |  end
                |
                |  class SessionStore{n}(T)
                |    ID_PATTERN = /^ses_[a-z0-9]+${'$'}/i
                |    property capacity : Int32
                |    @items = {} of String => T
                |
                |    def initialize(@capacity = 64)
                |    end
                |
                |    def load(id : String) : T?
                |      raise ArgumentError.new("bad id: #{id}") unless id =~ ID_PATTERN
                |      response = HTTP::Client.get("http://localhost:4096/session/#{id}?limit=#{@capacity * 2}")
                |      return nil unless response.success?
                |      session = T.from_json(response.body)
                |      @items[id] = session
                |      @items.shift if @items.size > @capacity
                |      session
                |    rescue ex : JSON::ParseException
                |      STDERR.puts "load failed: #{ex.message}"
                |      nil
                |    end
                |
                |    def size : Int32
                |      @items.values.count { |s| s.tokens > 0x1F } + 1_000
                |    end
                |
                |    macro def_status(*names)
                |      {% for name in names %}
                |        def {{name.id}}? : Bool
                |          @status == Status{n}::{{name.id.capitalize}}
                |        end
                |      {% end %}
                |    end
                |  end
                |end
                |
                |store = Opencode::SessionStore{n}(Opencode::Session).new(capacity: 32)
                |case store.size
                |when 0     then puts "empty"
                |when 1..10 then puts "small: #{store.size}"
                |else
                |  puts <<-TEXT
                |    Store has #{store.size} sessions
                |    ratio: #{0.75_f32}, symbol: #{:busy}, char: #{'x'}
                |    TEXT
                |end
                |
                """.trimMargin(),
            "dart" to
                """
                |/// Session store {n}: keeps the latest revision per key.
                |library session_store_{n};
                |
                |import 'dart:async';
                |import 'dart:convert' show jsonDecode;
                |
                |enum Status { idle, busy, failed }
                |
                |typedef Loader<T> = Future<T?> Function(String path);
                |
                |final _idPattern{n} = RegExp(r'^ses_[a-z0-9]+${'$'}', caseSensitive: false);
                |
                |sealed class Event {}
                |
                |final class Loaded extends Event {
                |  Loaded(this.id);
                |  final String id;
                |}
                |
                |class SessionStore{n}<T extends Object> {
                |  SessionStore{n}({this.capacity = 64, required this.loader});
                |
                |  final int capacity;
                |  final Loader<T> loader;
                |  final _items = <String, T>{};
                |  final _events = StreamController<Event>.broadcast();
                |  Status _status = Status.idle;
                |
                |  Stream<Event> get events => _events.stream;
                |
                |  Future<T?> load(String id) async {
                |    if (!_idPattern{n}.hasMatch(id)) {
                |      throw ArgumentError.value(id, 'id', 'must look like ses_*');
                |    }
                |    _status = Status.busy;
                |    try {
                |      final loaded = await loader('/session/${'$'}id?limit=${'$'}{capacity * 2}');
                |      if (loaded == null) return null;
                |      _items[id] = loaded;
                |      if (_items.length > capacity) _items.remove(_items.keys.first);
                |      _events.add(Loaded(id));
                |      return loaded;
                |    } on FormatException catch (e, stack) {
                |      print('load failed: ${'$'}{e.message}\n${'$'}stack');
                |      return null;
                |    } finally {
                |      _status = Status.idle;
                |    }
                |  }
                |
                |  int get size => _items.values.where((item) => item.hashCode.isEven).length + 0x1F;
                |
                |  String describe() => switch (_status) {
                |        Status.idle => 'idle',
                |        Status.busy when size > 1000 => 'busy and large',
                |        _ => ""${'"'}
                |Store with ${'$'}size items
                |  ratio: ${'$'}{0.75 * 2}
                |""${'"'},
                |      };
                |}
                |
                |Future<void> main() async {
                |  final store = SessionStore{n}<Map<String, dynamic>>(
                |    loader: (path) async => jsonDecode('{"path": "${'$'}path"}') as Map<String, dynamic>?,
                |  );
                |  await for (final event in store.events.take(1)) {
                |    print(event is Loaded ? 'loaded ${'$'}{event.id}' : 'other');
                |  }
                |}
                |
                """.trimMargin(),
            "elixir" to
                """
                |defmodule Opencode.SessionStore{n} do
                |  @moduledoc ""${'"'}
                |  Session store {n}: keeps the latest revision per key.
                |
                |      iex> Opencode.SessionStore{n}.size(%{})
                |      1000
                |  ""${'"'}
                |  use GenServer
                |  require Logger
                |  alias Opencode.{Session, Client}
                |
                |  @max_items 64
                |  @id_pattern ~r/^ses_[a-z0-9]+${'$'}/i
                |
                |  defstruct items: %{}, revision: 0, status: :idle
                |
                |  @type t :: %__MODULE__{items: map(), revision: non_neg_integer(), status: atom()}
                |
                |  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)
                |
                |  @impl true
                |  def init(_opts), do: {:ok, %__MODULE__{}}
                |
                |  @spec load(String.t()) :: {:ok, Session.t()} | {:error, term()}
                |  def load(id) when is_binary(id) do
                |    if Regex.match?(@id_pattern, id) do
                |      GenServer.call(__MODULE__, {:load, id}, 12_000)
                |    else
                |      {:error, "bad id: #{inspect(id)}"}
                |    end
                |  end
                |
                |  @impl true
                |  def handle_call({:load, id}, _from, %{items: items} = state) do
                |    with {:ok, %{status: 200, body: body}} <- Client.get("/session/#{id}?limit=#{@max_items * 2}"),
                |         {:ok, session} <- Session.decode(body) do
                |      items = items |> Map.put(id, session) |> evict()
                |      {:reply, {:ok, session}, %{state | items: items, revision: state.revision + 1}}
                |    else
                |      {:ok, %{status: status}} ->
                |        {:reply, {:error, status}, state}
                |
                |      {:error, reason} = error ->
                |        Logger.warning("load failed: #{inspect(reason)}")
                |        {:reply, error, state}
                |    end
                |  end
                |
                |  defp evict(items) when map_size(items) > @max_items, do: items |> Enum.drop(1) |> Map.new()
                |  defp evict(items), do: items
                |
                |  def size(items) do
                |    items
                |    |> Map.values()
                |    |> Enum.count(fn %{tokens: tokens} -> tokens > 0x1F end)
                |    |> Kernel.+(1_000)
                |  end
                |
                |  def describe(status) do
                |    case status do
                |      :idle -> ~s(idle "and" resting)
                |      {:busy, since} -> "busy since #{since}"
                |      _ -> ~S""${'"'}
                |      raw #{not interpolated}
                |      ""${'"'}
                |    end
                |  end
                |end
                |
                """.trimMargin(),
            "erlang" to
                """
                |%%% Session store {n}: keeps the latest revision per key.
                |-module(session_store_{n}).
                |-behaviour(gen_server).
                |
                |-export([start_link/0, load/1, size/0]).
                |-export([init/1, handle_call/3, handle_cast/2]).
                |
                |-include_lib("kernel/include/logger.hrl").
                |
                |-define(MAX_ITEMS, 64).
                |-define(TIMEOUT, 12000).
                |
                |-record(state, {items = #{} :: #{binary() => map()},
                |                revision = 0 :: non_neg_integer(),
                |                status = idle :: idle | busy}).
                |
                |-type session_id() :: binary().
                |-spec load(session_id()) -> {ok, map()} | {error, term()}.
                |
                |start_link() ->
                |    gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).
                |
                |load(Id) when is_binary(Id) ->
                |    case re:run(Id, "^ses_[a-z0-9]+${'$'}", [caseless]) of
                |        {match, _} -> gen_server:call(?MODULE, {load, Id}, ?TIMEOUT);
                |        nomatch -> {error, {bad_id, Id}}
                |    end.
                |
                |size() ->
                |    gen_server:call(?MODULE, size).
                |
                |init([]) ->
                |    {ok, #state{}}.
                |
                |handle_call({load, Id}, _From, #state{items = Items, revision = Rev} = State) ->
                |    Url = <<"/session/", Id/binary, "?limit=", (integer_to_binary(?MAX_ITEMS * 2))/binary>>,
                |    case httpc:request(get, {binary_to_list(Url), []}, [], [{body_format, binary}]) of
                |        {ok, {{_, 200, _}, _Headers, Body}} ->
                |            Session = json:decode(Body),
                |            Items1 = evict(maps:put(Id, Session, Items)),
                |            {reply, {ok, Session}, State#state{items = Items1, revision = Rev + 1}};
                |        {ok, {{_, Status, _}, _, _}} ->
                |            {reply, {error, Status}, State};
                |        {error, Reason} = Error ->
                |            ?LOG_WARNING("load failed: ~p", [Reason]),
                |            {reply, Error, State}
                |    end;
                |handle_call(size, _From, #state{items = Items} = State) ->
                |    Count = length([S || #{<<"tokens">> := T} = S <- maps:values(Items), T > 16#1F]),
                |    {reply, Count + 1000, State}.
                |
                |handle_cast(_Msg, State) ->
                |    {noreply, State}.
                |
                |evict(Items) when map_size(Items) > ?MAX_ITEMS ->
                |    [Oldest | _] = lists:sort(maps:keys(Items)),
                |    maps:remove(Oldest, Items);
                |evict(Items) ->
                |    Items.
                |
                |describe_{n}(Status) ->
                |    receive
                |        {status, S} when S =:= busy; S =:= idle -> io:format("~s~n", [S])
                |    after 500 ->
                |        {Status, ${'$'}x, 2.5e-3, 'quoted atom'}
                |    end.
                |
                """.trimMargin(),
            "fortran-fixed-form" to
                """
                |C     SESSION STORE {n}: SUMS TOKEN COUNTS PER SESSION.
                |C     LABELS IN COLUMNS 1-5, CONTINUATION IN COLUMN 6.
                |      PROGRAM SESS{n}
                |      IMPLICIT NONE
                |      INTEGER MAXITM
                |      PARAMETER (MAXITM = 64)
                |      INTEGER TOKENS(MAXITM), I, N, TOTAL
                |      REAL RATIO
                |      DOUBLE PRECISION AVG
                |      CHARACTER*16 IDS(MAXITM)
                |      LOGICAL DONE
                |      COMMON /STORE{n}/ TOKENS, IDS
                |      DATA RATIO /0.75/, DONE /.FALSE./
                |*
                |      N = 0
                |      TOTAL = 0
                |      OPEN (UNIT=10, FILE='SESSIONS.DAT', STATUS='OLD', ERR=900)
                |   10 CONTINUE
                |      READ (10, 100, END=20) IDS(N+1), TOKENS(N+1)
                |  100 FORMAT (A16, I7)
                |      N = N + 1
                |      IF (N .LT. MAXITM) GOTO 10
                |   20 CLOSE (10)
                |C
                |      DO 30 I = 1, N
                |         TOTAL = TOTAL + TOKENS(I)
                |         IF (TOKENS(I) .GT. 1000 .AND. .NOT. DONE) THEN
                |            WRITE (*, 200) IDS(I), TOKENS(I)
                |         ELSE IF (TOKENS(I) .EQ. 0) THEN
                |            DONE = .TRUE.
                |         END IF
                |   30 CONTINUE
                |      IF (N .GT. 0) THEN
                |         AVG = DBLE(TOTAL) / DBLE(N) * 1.0D0
                |         WRITE (*, '(A, F10.2, A, E12.4)') ' AVERAGE: ', AVG,
                |     &      ' RATIO: ', RATIO
                |      END IF
                |  200 FORMAT (1X, 'LARGE SESSION ', A, ' WITH ', I7, ' TOKENS')
                |      CALL REPORT{n}(TOTAL, N)
                |      STOP
                |  900 WRITE (*, *) 'CANNOT OPEN SESSIONS.DAT'
                |      STOP 1
                |      END
                |C
                |      SUBROUTINE REPORT{n}(TOTAL, N)
                |      INTEGER TOTAL, N
                |      CHARACTER*40 MSG
                |      MSG = 'TOTAL TOKENS'' COUNT'
                |      PRINT *, MSG, TOTAL, N
                |      RETURN
                |      END
                |
                """.trimMargin(),
            "fortran-free-form" to
                """
                |!> Session store {n}: keeps the latest revision per key.
                |module session_store_{n}
                |  use, intrinsic :: iso_fortran_env, only: int64, real64, error_unit
                |  implicit none
                |  private
                |  public :: session_t, store_t, load_session
                |
                |  integer, parameter :: max_items = 64
                |  real(real64), parameter :: ratio = 0.75_real64
                |
                |  type :: session_t
                |    character(len=:), allocatable :: id
                |    integer(int64) :: tokens = 0_int64
                |    logical :: busy = .false.
                |  end type session_t
                |
                |  type :: store_t
                |    type(session_t), allocatable :: items(:)
                |    integer :: count = 0
                |  contains
                |    procedure :: size => store_size
                |    procedure :: add => store_add
                |  end type store_t
                |
                |contains
                |
                |  pure function store_size(self) result(n)
                |    class(store_t), intent(in) :: self
                |    integer :: n
                |    n = count(self%items(1:self%count)%tokens > int(z'1F', int64)) + 1000
                |  end function store_size
                |
                |  subroutine store_add(self, item)
                |    class(store_t), intent(inout) :: self
                |    type(session_t), intent(in) :: item
                |    if (.not. allocated(self%items)) allocate(self%items(max_items))
                |    if (self%count >= max_items) then
                |      self%items(1:max_items - 1) = self%items(2:max_items)
                |      self%count = max_items - 1
                |    end if
                |    self%count = self%count + 1
                |    self%items(self%count) = item
                |  end subroutine store_add
                |
                |  subroutine load_session(store, id, ierr)
                |    type(store_t), intent(inout) :: store
                |    character(len=*), intent(in) :: id
                |    integer, intent(out) :: ierr
                |    integer :: unit, tokens
                |    character(len=256) :: path
                |
                |    write (path, '(a, a, a)') "sessions/", trim(id), ".dat"
                |    open (newunit=unit, file=trim(path), status='old', action='read', iostat=ierr)
                |    if (ierr /= 0) then
                |      write (error_unit, '(a, i0)') 'cannot open '//trim(path)//', iostat=', ierr
                |      return
                |    end if
                |    read (unit, *, iostat=ierr) tokens
                |    close (unit)
                |    select case (tokens)
                |    case (:0)
                |      ierr = 1
                |    case (1:999)
                |      call store%add(session_t(id=id, tokens=int(tokens, int64)))
                |    case default
                |      call store%add(session_t(id=id, tokens=int(tokens * ratio, int64), &
                |                               busy=.true.))
                |    end select
                |  end subroutine load_session
                |
                |end module session_store_{n}
                |
                """.trimMargin(),
            "fsharp" to
                """
                |/// Session store {n}: keeps the latest revision per key.
                |module Opencode.SessionStore{n}
                |
                |open System
                |open System.Net.Http
                |open System.Text.RegularExpressions
                |
                |type Status =
                |    | Idle
                |    | Busy of since: DateTime
                |    | Failed of message: string * code: int
                |
                |[<CLIMutable>]
                |type Session = { Id: string; Title: string; Tokens: int64 }
                |
                |(* Block comments (* nest *) in F#. *)
                |let private idPattern = Regex(@"^ses_[a-z0-9]+${'$'}", RegexOptions.IgnoreCase)
                |let [<Literal>] MaxItems = 64
                |
                |type SessionStore{n}(client: HttpClient, ?capacity: int) =
                |    let capacity = defaultArg capacity MaxItems
                |    let items = Collections.Generic.Dictionary<string, Session>()
                |    let mutable status = Idle
                |
                |    member _.Status = status
                |
                |    member this.LoadAsync(id: string) =
                |        task {
                |            if not (idPattern.IsMatch id) then
                |                invalidArg (nameof id) ${'$'}"bad id: {id}"
                |            status <- Busy DateTime.UtcNow
                |            try
                |                let! body = client.GetStringAsync(${'$'}"/session/{id}?limit={capacity * 2}")
                |                let session = Text.Json.JsonSerializer.Deserialize<Session> body
                |                items[id] <- session
                |                if items.Count > capacity then
                |                    items.Remove(Seq.head items.Keys) |> ignore
                |                status <- Idle
                |                return Some session
                |            with
                |            | :? HttpRequestException as ex ->
                |                status <- Failed(ex.Message, 0x1F)
                |                return None
                |        }
                |
                |    member _.Size =
                |        items.Values
                |        |> Seq.filter (fun s -> s.Tokens % 2L = 0L)
                |        |> Seq.length
                |        |> (+) 1_000
                |
                |let describe status =
                |    match status with
                |    | Idle -> "idle"
                |    | Busy since when since > DateTime.MinValue -> sprintf "busy since %O" since
                |    | Busy _ -> "busy"
                |    | Failed (message, code) -> ${'$'}""${'"'}failed "{message}" with {code:x}""${'"'}
                |
                |let squares = [ for x in 1..10 do if x % 2 = 0 then yield x * x ]
                |let ratio, flag, ch = 0.75, true, 'x'
                |
                """.trimMargin(),
            "graphql" to
                """
                |""${'"'}
                |Session schema {n}: queries, mutations, and subscriptions for sessions.
                |""${'"'}
                |schema {
                |  query: Query
                |  mutation: Mutation
                |  subscription: Subscription
                |}
                |
                |scalar DateTime @specifiedBy(url: "https://scalars.graphql.org/andimarek/date-time")
                |
                |directive @auth(requires: Role = USER) on OBJECT | FIELD_DEFINITION
                |
                |enum Status{n} {
                |  IDLE
                |  BUSY
                |  "The last call failed."
                |  FAILED @deprecated(reason: "Use ERROR")
                |  ERROR
                |}
                |
                |interface Node {
                |  id: ID!
                |}
                |
                |type Session{n} implements Node @auth {
                |  id: ID!
                |  title: String!
                |  status: Status{n}!
                |  tokens(kind: TokenKind = INPUT): Int
                |  ratio: Float
                |  messages(first: Int = 20, after: String): MessageConnection!
                |  createdAt: DateTime
                |}
                |
                |union SearchResult = Session{n} | Message
                |
                |input SessionFilter {
                |  status: [Status{n}!]
                |  titleContains: String
                |  minTokens: Int = 0
                |}
                |
                |type Query {
                |  session(id: ID!): Session{n}
                |  sessions(filter: SessionFilter, limit: Int = 64): [Session{n}!]!
                |  search(text: String!): [SearchResult!]!
                |}
                |
                |type Mutation {
                |  renameSession(id: ID!, title: String!): Session{n} @auth(requires: ADMIN)
                |}
                |
                |# Client operations
                |query RecentSessions{n}(${'$'}limit: Int = 10, ${'$'}withMessages: Boolean! = false) {
                |  sessions(filter: { status: [IDLE, BUSY], minTokens: 100 }, limit: ${'$'}limit) {
                |    ...SessionFields
                |    messages(first: 5) @include(if: ${'$'}withMessages) {
                |      edges { node { id text } }
                |    }
                |  }
                |}
                |
                |fragment SessionFields on Session{n} {
                |  id
                |  title
                |  status
                |  ratio
                |  alias: tokens(kind: OUTPUT)
                |}
                |
                |mutation Rename(${'$'}id: ID!) {
                |  renameSession(id: ${'$'}id, title: "Renamed \"session\" {n}") { id title }
                |}
                |
                """.trimMargin(),
            "groovy" to
                """
                |/**
                | * Session store {n}: keeps the latest revision per key.
                | */
                |package dev.opencode.bench.store{n}
                |
                |import groovy.json.JsonSlurper
                |import groovy.transform.ToString
                |
                |@ToString(includeNames = true)
                |class Session {
                |    String id
                |    String title
                |    long tokens = 0L
                |}
                |
                |class SessionStore{n} {
                |    static final int MAX_ITEMS = 64
                |    private final Map<String, Session> items = [:]
                |    def status = 'idle'
                |
                |    Session load(String id) {
                |        assert id ==~ /^ses_[a-z0-9]+${'$'}/ : "bad id: ${'$'}id"
                |        def url = "http://localhost:4096/session/${'$'}{id}?limit=${'$'}{MAX_ITEMS * 2}".toURL()
                |        def body = new JsonSlurper().parseText(url.text) as Map
                |        def session = new Session(id: id, title: body.title as String, tokens: body.tokens as long)
                |        items[id] = session
                |        if (items.size() > MAX_ITEMS) items.remove(items.keySet().first())
                |        return session
                |    }
                |
                |    int getSize() {
                |        items.values().count { it.tokens > 0x1F } + 1_000
                |    }
                |}
                |
                |def store = new SessionStore{n}()
                |['ses_1', 'ses_2', "ses_${'$'}{3}"].each { id ->
                |    try {
                |        println store.load(id as String)?.title ?: 'untitled'
                |    } catch (IOException | IllegalStateException e) {
                |        System.err.println "load failed: ${'$'}{e.message}"
                |    }
                |}
                |
                |def report = ""${'"'}
                |Store ${'$'}{store.class.simpleName}
                |  size: ${'$'}{store.size}
                |  ratio: ${'$'}{0.75G * 2}
                |""${'"'}
                |def raw = '''no ${'$'}interpolation here'''
                |def slashy = ${'$'}/C:\reports\${'$'}{store.status}/${'$'}
                |
                |switch (store.status) {
                |    case 'idle': println 'resting'; break
                |    case ~/busy.*/: println 'working'; break
                |    case [1, 2, 3]: break
                |    default: println "unknown ${'$'}{store.status}"
                |}
                |
                |task copyReports{n}(type: Copy) {
                |    from 'build/reports'
                |    into "${'$'}{buildDir}/archive"
                |    include '**/*.html'
                |}
                |
                """.trimMargin(),
            "haskell" to
                """
                |{-# LANGUAGE OverloadedStrings #-}
                |{-# LANGUAGE LambdaCase #-}
                |{- |
                |Module      : Session.Store{n}
                |Description : Keeps the latest revision per key.
                |
                |{- Comments nest in Haskell. -}
                |-}
                |module Session.Store{n}
                |  ( Session (..)
                |  , Status (..)
                |  , SessionStore
                |  , newStore
                |  , loadSession
                |  ) where
                |
                |import Control.Concurrent.STM (TVar, atomically, modifyTVar', newTVarIO, readTVarIO)
                |import Control.Exception (SomeException, try)
                |import qualified Data.Map.Strict as Map
                |import Data.Text (Text)
                |import qualified Data.Text as T
                |
                |data Status = Idle | Busy !Int | Failed { message :: Text, code :: Int }
                |  deriving (Show, Eq)
                |
                |data Session = Session
                |  { sessionId :: !Text
                |  , title     :: Text
                |  , tokens    :: {-# UNPACK #-} !Int
                |  } deriving (Show)
                |
                |newtype SessionStore = SessionStore (TVar (Map.Map Text Session))
                |
                |maxItems :: Int
                |maxItems = 64
                |
                |newStore :: IO SessionStore
                |newStore = SessionStore <${'$'}> newTVarIO Map.empty
                |
                |loadSession :: SessionStore -> (Text -> IO (Maybe Session)) -> Text -> IO (Either Text Session)
                |loadSession (SessionStore var) loader sid
                |  | not ("ses_" `T.isPrefixOf` sid) = pure (Left ("bad id: " <> sid))
                |  | otherwise = do
                |      result <- try (loader ("/session/" <> sid <> "?limit=" <> T.pack (show (maxItems * 2))))
                |      case result :: Either SomeException (Maybe Session) of
                |        Left err -> pure . Left ${'$'} T.pack (show err)
                |        Right Nothing -> pure (Left "not found")
                |        Right (Just session) -> do
                |          atomically ${'$'} modifyTVar' var (evict . Map.insert sid session)
                |          pure (Right session)
                |  where
                |    evict items
                |      | Map.size items > maxItems = Map.deleteMin items
                |      | otherwise = items
                |
                |describe{n} :: Status -> String
                |describe{n} = \case
                |  Idle -> "idle"
                |  Busy secs | secs > 0x1F -> "busy for " ++ show secs ++ "s"
                |  Busy _ -> "busy"
                |  Failed {message = m} -> 'x' : T.unpack m
                |
                |size{n} :: SessionStore -> IO Int
                |size{n} (SessionStore var) = do
                |  items <- readTVarIO var
                |  let counted = [t | Session {tokens = t} <- Map.elems items, even t]
                |  return ${'$'}! length counted + 1000 + round (0.75 :: Double)
                |
                """.trimMargin(),
            "julia" to
                """
                |""${'"'}
                |    SessionStore
                |
                |Session store {n}: keeps the latest revision per key.
                |""${'"'}
                |module SessionStores{n}
                |
                |using Dates
                |import Base: length, show
                |
                |export SessionStore, load!, describe
                |
                |@enum Status idle busy failed
                |
                |const MAX_ITEMS = 64
                |const ID_PATTERN = r"^ses_[a-z0-9]+${'$'}"i
                |
                |struct Session
                |    id::String
                |    title::String
                |    tokens::Int64
                |end
                |
                |mutable struct SessionStore{T}
                |    items::Dict{String,T}
                |    capacity::Int
                |    status::Status
                |    SessionStore{T}(capacity::Integer = MAX_ITEMS) where {T} = new{T}(Dict{String,T}(), capacity, idle)
                |end
                |
                |length(store::SessionStore) = count(s -> s.tokens > 0x1f, values(store.items)) + 1_000
                |
                |function load!(store::SessionStore{T}, id::AbstractString; fetch = http_get) where {T}
                |    occursin(ID_PATTERN, id) || throw(ArgumentError("bad id: ${'$'}id"))
                |    store.status = busy
                |    try
                |        body = fetch("/session/${'$'}(id)?limit=${'$'}(store.capacity * 2)")
                |        session = T(id, get(body, "title", ""), body["tokens"])
                |        store.items[id] = session
                |        length(store.items) > store.capacity && delete!(store.items, first(keys(store.items)))
                |        return session
                |    catch err
                |        @warn "load failed" exception = err
                |        nothing
                |    finally
                |        store.status = idle
                |    end
                |end
                |
                |describe(s::Status) = s == idle ? "idle" : s == busy ? "busy since ${'$'}(now())" : "failed"
                |
                |function show(io::IO, store::SessionStore)
                |    print(io, ""${'"'}
                |        SessionStore with ${'$'}(length(store)) items
                |          ratio: ${'$'}(0.75 * 2), complex: ${'$'}(1 + 2im), rational: ${'$'}(3 // 4)
                |        ""${'"'})
                |end
                |
                |#=
                |  Block comments #= nest =# in Julia.
                |=#
                |squares_{n} = [x^2 for x in 1:10 if iseven(x)]
                |matrix_{n} = [1.0 2.0; 3.0 4.5e-3]
                |macro twice(ex)
                |    return :(${'$'}(esc(ex)); ${'$'}(esc(ex)))
                |end
                |@twice println('x', :symbol, `ls -la`, raw"C:\path\${'$'}no")
                |
                |end # module
                |
                """.trimMargin(),
            "lua" to
                """
                |--[[
                |  Session store {n}: keeps the latest revision per key.
                |  Long comments use the same brackets as long strings.
                |]]
                |local json = require("dkjson")
                |local http = require("socket.http")
                |
                |local MAX_ITEMS = 64
                |local ID_PATTERN = "^ses_[%w_]+${'$'}"
                |
                |---@class SessionStore{n}
                |local SessionStore{n} = {}
                |SessionStore{n}.__index = SessionStore{n}
                |
                |function SessionStore{n}.new(capacity)
                |  local self = setmetatable({}, SessionStore{n})
                |  self.items, self.order = {}, {}
                |  self.capacity = capacity or MAX_ITEMS
                |  return self
                |end
                |
                |function SessionStore{n}:load(id)
                |  if not id:match(ID_PATTERN) then
                |    error(("bad id: %q"):format(id), 2)
                |  end
                |  local body, code = http.request("http://localhost:4096/session/" .. id .. "?limit=" .. self.capacity * 2)
                |  if code ~= 200 then
                |    return nil, "load failed: " .. tostring(code)
                |  end
                |  local ok, session = pcall(json.decode, body)
                |  if not ok then return nil, session end
                |  self.items[id] = session
                |  self.order[#self.order + 1] = id
                |  while #self.order > self.capacity do
                |    local oldest = table.remove(self.order, 1)
                |    self.items[oldest] = nil
                |  end
                |  return session
                |end
                |
                |function SessionStore{n}:size()
                |  local count = 0
                |  for _, session in pairs(self.items) do
                |    if (session.tokens or 0) > 0x1F and session.status ~= "failed" then
                |      count = count + 1
                |    end
                |  end
                |  return count + 1e3
                |end
                |
                |local summary = [[
                |Store summary
                |  items: %d
                |  ratio: %.2f
                |]]
                |local nested = [=[a long string with ]] inside]=]
                |local store = SessionStore{n}.new(32)
                |for i = 1, 3 do
                |  local session, err = store:load("ses_" .. i)
                |  print(session and session.title or err, i // 2, 2 ^ 10, #"len")
                |end
                |goto done
                |::done::
                |print(summary:format(store:size(), 0.75), nested, 'single \'quoted\'')
                |
                """.trimMargin(),
            "make" to
                """
                |# Makefile {n}: builds the session tools and their tests.
                |.DEFAULT_GOAL := all
                |SHELL := /bin/bash
                |.SHELLFLAGS := -eu -o pipefail -c
                |
                |PROJECT_{n} ?= session-store
                |VERSION     := ${'$'}(shell git describe --tags --always 2>/dev/null || echo 0.0.0)
                |CC          ?= cc
                |CFLAGS      += -O2 -Wall -Wextra -std=c11 -DVERSION=\"${'$'}(VERSION)\"
                |SRC_DIR     := src
                |BUILD_DIR   := build/${'$'}(PROJECT_{n})
                |SOURCES     := ${'$'}(wildcard ${'$'}(SRC_DIR)/*.c)
                |OBJECTS     := ${'$'}(patsubst ${'$'}(SRC_DIR)/%.c,${'$'}(BUILD_DIR)/%.o,${'$'}(SOURCES))
                |TESTS       = ${'$'}(filter %_test.c,${'$'}(SOURCES))
                |
                |ifeq (${'$'}(OS),Windows_NT)
                |    EXE := .exe
                |else ifneq (,${'$'}(findstring Darwin,${'$'}(shell uname -s)))
                |    LDFLAGS += -framework CoreFoundation
                |endif
                |
                |define compile_{n}
                |	@mkdir -p ${'$'}(dir ${'$'}@)
                |	${'$'}(CC) ${'$'}(CFLAGS) -c ${'$'}< -o ${'$'}@
                |endef
                |
                |.PHONY: all clean test install
                |
                |all: ${'$'}(BUILD_DIR)/${'$'}(PROJECT_{n})${'$'}(EXE)
                |
                |${'$'}(BUILD_DIR)/${'$'}(PROJECT_{n})${'$'}(EXE): ${'$'}(OBJECTS) | ${'$'}(BUILD_DIR)
                |	${'$'}(CC) ${'$'}(LDFLAGS) ${'$'}^ -o ${'$'}@
                |	@echo "linked ${'$'}@ (${'$'}(words ${'$'}^) objects)"
                |
                |${'$'}(BUILD_DIR)/%.o: ${'$'}(SRC_DIR)/%.c ${'$'}(SRC_DIR)/store.h
                |	${'$'}(call compile_{n})
                |
                |${'$'}(BUILD_DIR):
                |	mkdir -p ${'$'}@
                |
                |test: all
                |	@for t in ${'$'}(TESTS:.c=); do \
                |	  echo "running ${'$'}${'$'}t"; \
                |	  ./${'$'}(BUILD_DIR)/${'$'}${'$'}(basename ${'$'}${'$'}t) || exit 1; \
                |	done
                |
                |install: all
                |	install -m 0755 ${'$'}(BUILD_DIR)/${'$'}(PROJECT_{n})${'$'}(EXE) ${'$'}(DESTDIR)/usr/local/bin/
                |
                |clean:
                |	-rm -rf ${'$'}(BUILD_DIR) *.log
                |
                |include ${'$'}(wildcard deps/*.mk)
                |export PATH := ${'$'}(CURDIR)/bin:${'$'}(PATH)
                |
                """.trimMargin(),
            "matlab" to
                """
                |function results = session_report_{n}(sessions, varargin)
                |%SESSION_REPORT_{n} Sums token counts per session.
                |%   RESULTS = SESSION_REPORT_{n}(SESSIONS) returns a table of totals.
                |%   RESULTS = SESSION_REPORT_{n}(SESSIONS, 'Limit', N) keeps N rows.
                |
                |p = inputParser;
                |addRequired(p, 'sessions', @isstruct);
                |addParameter(p, 'Limit', 64, @(x) isnumeric(x) && x > 0);
                |parse(p, sessions, varargin{:});
                |limit = p.Results.Limit;
                |
                |%{
                |  Block comments span
                |  several lines.
                |%}
                |ids = {sessions.id};
                |tokens = [sessions.tokens];
                |mask = tokens > hex2dec('1F') & ~cellfun(@isempty, ids);
                |total = sum(tokens(mask), 'omitnan');
                |
                |if isempty(ids)
                |    warning('session:empty', 'No sessions in "%s"', inputname(1));
                |    results = table();
                |    return
                |elseif numel(ids) > limit
                |    [~, order] = sort(tokens, 'descend');
                |    ids = ids(order(1:limit));
                |    tokens = tokens(order(1:limit));
                |end
                |
                |ratio = tokens ./ max(total, 1) * 100;
                |results = table(ids', tokens', ratio', 'VariableNames', {'Id', 'Tokens', 'Percent'});
                |
                |for k = 1:height(results)
                |    switch results.Tokens(k)
                |        case {0, 1}
                |            label = "tiny";
                |        case num2cell(2:1000)
                |            label = "normal";
                |        otherwise
                |            label = sprintf('large (%d)', results.Tokens(k));
                |    end
                |    fprintf('%-16s %8d %6.2f%% %s\n', results.Id{k}, results.Tokens(k), results.Percent(k), label);
                |end
                |
                |try
                |    A = [1 2; 3 4.5e-3]';
                |    B = A \ [1; 2] .^ 2;
                |    disp(B(end));
                |catch err
                |    fprintf(2, 'solve failed: %s\n', err.message);
                |end
                |end
                |
                """.trimMargin(),
            "nim" to
                """
                |## Session store {n}: keeps the latest revision per key.
                |import std/[tables, strutils, strformat, httpclient, json, options]
                |
                |const
                |  MaxItems = 64
                |  Mask = 0x1F'i64
                |
                |type
                |  Status = enum
                |    stIdle = "idle", stBusy = "busy", stFailed = "failed"
                |
                |  Session* = object
                |    id*: string
                |    title*: string
                |    tokens*: int64
                |
                |  SessionStore{n}* = ref object
                |    items: OrderedTable[string, Session]
                |    capacity: Natural
                |    status: Status
                |
                |  LoadError* = object of CatchableError
                |
                |proc newSessionStore{n}*(capacity: Natural = MaxItems): SessionStore{n} =
                |  SessionStore{n}(items: initOrderedTable[string, Session](), capacity: capacity)
                |
                |proc evict(store: SessionStore{n}) =
                |  while store.items.len > store.capacity:
                |    for key in store.items.keys:
                |      store.items.del(key)
                |      break
                |
                |proc load*(store: SessionStore{n}, id: string): Option[Session] =
                |  if not id.startsWith("ses_"):
                |    raise newException(LoadError, &"bad id: {id}")
                |  store.status = stBusy
                |  defer: store.status = stIdle
                |  try:
                |    let client = newHttpClient(timeout = 12_000)
                |    let body = parseJson(client.getContent(fmt"http://localhost:4096/session/{id}?limit={store.capacity * 2}"))
                |    let session = Session(id: id, title: body{"title"}.getStr("untitled"), tokens: body["tokens"].getBiggestInt)
                |    store.items[id] = session
                |    store.evict()
                |    result = some(session)
                |  except CatchableError as e:
                |    raise newException(LoadError, "load failed: " & e.msg)
                |
                |func size*(store: SessionStore{n}): int =
                |  for session in store.items.values:
                |    if session.tokens > Mask: inc result
                |  result += 1_000
                |
                |#[ Multi-line comments
                |   #[ nest ]# in Nim. ]#
                |when isMainModule:
                |  let store = newSessionStore{n}()
                |  echo store.size, ' ', 0.75, " ", ""${'"'}raw "triple" string""${'"'}
                |  case store.status
                |  of stIdle: echo "resting"
                |  of stBusy, stFailed: discard
                |
                """.trimMargin(),
            "objective-c" to
                """
                |// SessionStore{n}.m: keeps the latest revision per key.
                |#import <Foundation/Foundation.h>
                |#import "SessionStore.h"
                |
                |#define MAX_ITEMS 64
                |static NSString *const kSessionPath{n} = @"/session/%@?limit=%d";
                |
                |typedef NS_ENUM(NSInteger, SessionStatus) {
                |    SessionStatusIdle,
                |    SessionStatusBusy,
                |    SessionStatusFailed = 0x1F,
                |};
                |
                |@interface SessionStore{n} : NSObject
                |@property (nonatomic, readonly) NSUInteger capacity;
                |@property (nonatomic, assign) SessionStatus status;
                |- (instancetype)initWithCapacity:(NSUInteger)capacity NS_DESIGNATED_INITIALIZER;
                |@end
                |
                |@implementation SessionStore{n} {
                |    NSMutableDictionary<NSString *, NSDictionary *> *_items;
                |    dispatch_queue_t _queue;
                |}
                |
                |- (instancetype)initWithCapacity:(NSUInteger)capacity {
                |    if ((self = [super init])) {
                |        _capacity = capacity ?: MAX_ITEMS;
                |        _items = [NSMutableDictionary dictionary];
                |        _queue = dispatch_queue_create("dev.opencode.store", DISPATCH_QUEUE_SERIAL);
                |    }
                |    return self;
                |}
                |
                |- (void)loadSession:(NSString *)sessionID completion:(void (^)(NSDictionary *, NSError *))completion {
                |    NSString *path = [NSString stringWithFormat:kSessionPath{n}, sessionID, (int)(self.capacity * 2)];
                |    NSURL *url = [NSURL URLWithString:path relativeToURL:[NSURL URLWithString:@"http://localhost:4096"]];
                |    self.status = SessionStatusBusy;
                |    __weak typeof(self) weakSelf = self;
                |    [[NSURLSession.sharedSession dataTaskWithURL:url completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                |        __strong typeof(weakSelf) strongSelf = weakSelf;
                |        if (!strongSelf || error != nil) {
                |            NSLog(@"load failed: %@", error.localizedDescription);
                |            completion(nil, error);
                |            return;
                |        }
                |        NSDictionary *session = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
                |        dispatch_async(strongSelf->_queue, ^{
                |            strongSelf->_items[sessionID] = session;
                |            if (strongSelf->_items.count > strongSelf.capacity) {
                |                [strongSelf->_items removeObjectForKey:strongSelf->_items.allKeys.firstObject];
                |            }
                |            strongSelf.status = SessionStatusIdle;
                |            completion(session, nil);
                |        });
                |    }] resume];
                |}
                |
                |- (NSUInteger)size {
                |    NSPredicate *large = [NSPredicate predicateWithFormat:@"tokens > %d", 1000];
                |    NSArray *flags = @[@YES, @2, @3.5, @"ses_1"];
                |    return [[_items.allValues filteredArrayUsingPredicate:large] count] + flags.count;
                |}
                |
                |@end
                |
                """.trimMargin(),
            "ocaml" to
                """
                |(* Session store {n}: keeps the latest revision per key.
                |   (* Comments nest in OCaml. *) *)
                |open Printf
                |
                |module StringMap = Map.Make (String)
                |
                |type status = Idle | Busy of float | Failed of { message : string; code : int }
                |
                |type session = { id : string; title : string; mutable tokens : int }
                |
                |exception Not_found_{n} of string
                |
                |let max_items = 64
                |let id_pattern = Str.regexp {|^ses_[a-z0-9]+${'$'}|}
                |
                |module type LOADER = sig
                |  val fetch : string -> session option Lwt.t
                |end
                |
                |module Store{n} (L : LOADER) = struct
                |  type t = { mutable items : session StringMap.t; mutable status : status; capacity : int }
                |
                |  let create ?(capacity = max_items) () = { items = StringMap.empty; status = Idle; capacity }
                |
                |  let evict store =
                |    if StringMap.cardinal store.items > store.capacity then
                |      let oldest, _ = StringMap.min_binding store.items in
                |      store.items <- StringMap.remove oldest store.items
                |
                |  let load store id =
                |    let open Lwt.Syntax in
                |    if not (Str.string_match id_pattern id 0) then invalid_arg ("bad id: " ^ id);
                |    store.status <- Busy (Unix.gettimeofday ());
                |    let* loaded = L.fetch (sprintf "/session/%s?limit=%d" id (store.capacity * 2)) in
                |    match loaded with
                |    | None -> raise (Not_found_{n} id)
                |    | Some session ->
                |        store.items <- StringMap.add id session store.items;
                |        evict store;
                |        store.status <- Idle;
                |        Lwt.return session
                |
                |  let size store =
                |    StringMap.fold (fun _ s acc -> if s.tokens > 0x1F then acc + 1 else acc) store.items 1_000
                |end
                |
                |let describe = function
                |  | Idle -> "idle"
                |  | Busy since when since > 0. -> sprintf "busy since %.2f" since
                |  | Busy _ -> "busy"
                |  | Failed { message; code } -> sprintf "failed %S (%d) %c" message code 'x'
                |
                |let squares = List.init 10 (fun i -> i * i) |> List.filter (fun x -> x mod 2 = 0)
                |let ratio = 0.75 *. 2.0 and flag = true
                |let () = print_endline (String.concat ", " (List.map string_of_int squares))
                |
                """.trimMargin(),
            "pascal" to
                """
                |{ Session store {n}: keeps the latest revision per key. }
                |unit SessionStore{n};
                |
                |{${'$'}mode objfpc}{${'$'}H+}
                |
                |interface
                |
                |uses
                |  Classes, SysUtils, Generics.Collections, fpjson, jsonparser;
                |
                |const
                |  MaxItems = 64;
                |  Mask = ${'$'}1F;
                |  Ratio: Double = 0.75;
                |
                |type
                |  TStatus = (stIdle, stBusy, stFailed);
                |
                |  TSession = record
                |    Id: string;
                |    Title: string;
                |    Tokens: Int64;
                |  end;
                |
                |  ESessionNotFound = class(Exception);
                |
                |  TSessionStore = class(TObject)
                |  private
                |    FItems: specialize TDictionary<string, TSession>;
                |    FCapacity: Integer;
                |    FStatus: TStatus;
                |    function GetSize: Integer;
                |  public
                |    constructor Create(ACapacity: Integer = MaxItems);
                |    destructor Destroy; override;
                |    function Load(const AId: string): TSession;
                |    property Size: Integer read GetSize;
                |  end;
                |
                |implementation
                |
                |constructor TSessionStore.Create(ACapacity: Integer);
                |begin
                |  inherited Create;
                |  FItems := specialize TDictionary<string, TSession>.Create;
                |  FCapacity := ACapacity;
                |end;
                |
                |destructor TSessionStore.Destroy;
                |begin
                |  FItems.Free;
                |  inherited Destroy;
                |end;
                |
                |function TSessionStore.Load(const AId: string): TSession;
                |var
                |  Json: TJSONData;
                |  Key: string;
                |begin
                |  if Pos('ses_', AId) <> 1 then
                |    raise ESessionNotFound.CreateFmt('bad id: ''%s''', [AId]);
                |  FStatus := stBusy;
                |  Json := GetJSON('{"title": "First", "tokens": 120}');
                |  try
                |    Result.Id := AId;
                |    Result.Title := Json.FindPath('title').AsString;
                |    Result.Tokens := Json.FindPath('tokens').AsInt64 * 2;
                |    FItems.AddOrSetValue(AId, Result);
                |    if FItems.Count > FCapacity then
                |      for Key in FItems.Keys do
                |      begin
                |        FItems.Remove(Key);
                |        Break;
                |      end;
                |  finally
                |    FStatus := stIdle;
                |    Json.Free;
                |  end;
                |end;
                |
                |(* Counts large sessions. *)
                |function TSessionStore.GetSize: Integer;
                |var
                |  Session: TSession;
                |begin
                |  Result := 1000;
                |  for Session in FItems.Values do
                |    if (Session.Tokens > Mask) and not (FStatus = stFailed) then
                |      Inc(Result);
                |  // Character codes and sets.
                |  if #65 in ['A'..'Z'] then Result := Result div 2 + Ord('x') mod 3;
                |end;
                |
                |end. // unit SessionStore{n}
                |
                """.trimMargin(),
            "perl" to
                """
                |#!/usr/bin/env perl
                |# Session report {n}: sums token counts per session.
                |use strict;
                |use warnings;
                |use feature qw(say signatures);
                |no warnings qw(experimental::signatures);
                |
                |package SessionStore{n} {
                |    use constant MAX_ITEMS => 64;
                |
                |    sub new (${'$'}class, %args) {
                |        return bless { items => {}, order => [], capacity => ${'$'}args{capacity} // MAX_ITEMS }, ${'$'}class;
                |    }
                |
                |    sub load (${'$'}self, ${'$'}id) {
                |        die "bad id: ${'$'}id\n" unless ${'$'}id =~ m/^ses_[a-z0-9]+${'$'}/i;
                |        my ${'$'}body = qx(curl -s "http://localhost:4096/session/${'$'}id?limit=@{[ ${'$'}self->{capacity} * 2 ]}");
                |        my (${'$'}title, ${'$'}tokens) = ${'$'}body =~ /"title":"([^"]*)".*?"tokens":(\d+)/s or return;
                |        ${'$'}self->{items}{${'$'}id} = { title => ${'$'}title, tokens => ${'$'}tokens + 0 };
                |        push @{ ${'$'}self->{order} }, ${'$'}id;
                |        delete ${'$'}self->{items}{ shift @{ ${'$'}self->{order} } } while @{ ${'$'}self->{order} } > ${'$'}self->{capacity};
                |        return ${'$'}self->{items}{${'$'}id};
                |    }
                |
                |    sub size (${'$'}self) {
                |        return 1_000 + scalar grep { ${'$'}_->{tokens} > 0x1F } values %{ ${'$'}self->{items} };
                |    }
                |}
                |
                |=pod
                |
                |=head1 NAME
                |
                |session_report_{n}.pl - totals per session
                |
                |=cut
                |
                |my ${'$'}store = SessionStore{n}->new(capacity => 32);
                |my %totals;
                |my @ids = qw(ses_1 ses_2 ses_3);
                |
                |for my ${'$'}id (@ids) {
                |    my ${'$'}session = eval { ${'$'}store->load(${'$'}id) } or do {
                |        warn "load failed: ${'$'}@" if ${'$'}@;
                |        next;
                |    };
                |    ${'$'}totals{${'$'}id} += ${'$'}session->{tokens};
                |    (my ${'$'}short = ${'$'}id) =~ s/^ses_//;
                |    printf "%-10s %8d\n", ${'$'}short, ${'$'}totals{${'$'}id};
                |}
                |
                |print <<"REPORT";
                |Store size: @{[ ${'$'}store->size ]}
                |Sessions: ${'$'}{\ scalar @ids}, ratio: 0.75
                |REPORT
                |
                |print <<~'RAW';
                |    No ${'$'}interpolation here.
                |    RAW
                |
                |say sprintf('%.2f', 3.14159), " ", join(', ', map { ${'$'}_ * 2 } 1 .. 5);
                |
                """.trimMargin(),
            "powershell" to
                """
                |<#
                |.SYNOPSIS
                |    Session report {n}: sums token counts per session.
                |#>
                |[CmdletBinding()]
                |param(
                |    [Parameter(Mandatory = ${'$'}true, ValueFromPipeline = ${'$'}true)]
                |    [ValidatePattern('^ses_[a-z0-9]+${'$'}')]
                |    [string[]] ${'$'}Id,
                |    [ValidateRange(1, 1024)]
                |    [int] ${'$'}Limit = 64,
                |    [switch] ${'$'}Detailed
                |)
                |
                |begin {
                |    Set-StrictMode -Version Latest
                |    ${'$'}ErrorActionPreference = 'Stop'
                |    ${'$'}script:Totals{n} = @{}
                |    ${'$'}baseUri = "http://localhost:4096"
                |}
                |
                |process {
                |    foreach (${'$'}sessionId in ${'$'}Id) {
                |        try {
                |            ${'$'}session = Invoke-RestMethod -Uri "${'$'}baseUri/session/${'$'}(${'$'}sessionId)?limit=${'$'}(${'$'}Limit * 2)" -TimeoutSec 12
                |            ${'$'}script:Totals{n}[${'$'}sessionId] = [int64]${'$'}session.tokens
                |            if (${'$'}Detailed -and ${'$'}session.tokens -gt 0x1F) {
                |                Write-Verbose "large session ${'$'}sessionId (${'$'}(${'$'}session.tokens) tokens)"
                |            }
                |        }
                |        catch [System.Net.WebException] {
                |            Write-Warning "load failed: ${'$'}(${'$'}_.Exception.Message)"
                |        }
                |    }
                |}
                |
                |end {
                |    ${'$'}report = ${'$'}script:Totals{n}.GetEnumerator() |
                |        Where-Object { ${'$'}_.Value -ge 0 } |
                |        Sort-Object -Property Value -Descending |
                |        Select-Object -First ${'$'}Limit
                |
                |    ${'$'}summary = @"
                |Sessions: ${'$'}(${'$'}report.Count)
                |Ratio:    ${'$'}(0.75 * 2)
                |Path:     ${'$'}env:HOME\reports
                |"@
                |    ${'$'}raw = @'
                |No ${'$'}interpolation in single-quoted here-strings.
                |'@
                |    switch -Regex (${'$'}summary) {
                |        '^Sessions: 0' { Write-Host 'empty' -ForegroundColor Yellow; break }
                |        default { ${'$'}summary | Out-File -FilePath "report-${'$'}Limit.txt" -Encoding utf8 }
                |    }
                |    [PSCustomObject]@{ Total = (${'$'}report | Measure-Object -Property Value -Sum).Sum; Raw = ${'$'}raw }
                |}
                |
                """.trimMargin(),
            "prolog" to
                """
                |% Session store {n}: keeps the latest revision per key.
                |:- module(session_store_{n}, [load_session/2, store_size/1, describe/2]).
                |:- use_module(library(lists)).
                |:- dynamic session/3.
                |
                |/* Facts: session(Id, Title, Tokens). */
                |session('ses_1', "First session", 120).
                |session('ses_2', "Second session", 3400).
                |session(ses_3, 'Quoted \'title\'', 0x1F).
                |
                |max_items(64).
                |
                |%% load_session(+Id, -Session) is semidet.
                |load_session(Id, session(Id, Title, Tokens)) :-
                |    atom(Id),
                |    sub_atom(Id, 0, _, _, ses_),
                |    (   session(Id, Title, Tokens)
                |    ->  true
                |    ;   format(atom(Url), "http://localhost:4096/session/~w", [Id]),
                |        catch(fetch(Url, Title, Tokens), Error,
                |              ( print_message(warning, Error), fail )),
                |        assertz(session(Id, Title, Tokens)),
                |        evict
                |    ).
                |
                |evict :-
                |    max_items(Max),
                |    aggregate_all(count, session(_, _, _), Count),
                |    (   Count > Max
                |    ->  once(retract(session(_, _, _))),
                |        evict
                |    ;   true
                |    ).
                |
                |store_size(Size) :-
                |    findall(T, (session(_, _, T), T > 1000), Large),
                |    length(Large, N),
                |    Size is N + 1000.
                |
                |describe(Id, Description) :-
                |    session(Id, Title, Tokens),
                |    (   Tokens >= 1000 -> Kind = large
                |    ;   Tokens =:= 0 -> Kind = empty
                |    ;   Kind = normal
                |    ),
                |    format(string(Description), "~s: ~d tokens (~a)", [Title, Tokens, Kind]).
                |
                |total_tokens_{n}(Total) :-
                |    findall(T, session(_, _, T), Ts),
                |    sum_list(Ts, Total0),
                |    Total is Total0 * 0.75 + 2.5e-3,
                |    \+ Total < 0,
                |    !.
                |
                |fetch(_Url, "Fetched", 0'a).
                |
                """.trimMargin(),
            "r" to
                """
                |#' Session report {n}: sums token counts per session.
                |#'
                |#' @param sessions A data frame with `id`, `title`, and `tokens` columns.
                |#' @param limit How many rows to keep.
                |#' @return A data frame of totals, largest first.
                |#' @export
                |session_report_{n} <- function(sessions, limit = 64L, ...) {
                |  stopifnot(is.data.frame(sessions), all(c("id", "tokens") %in% names(sessions)))
                |  if (nrow(sessions) == 0) {
                |    warning("no sessions in ", deparse(substitute(sessions)))
                |    return(invisible(NULL))
                |  }
                |  valid <- grepl("^ses_[a-z0-9]+${'$'}", sessions${'$'}id, ignore.case = TRUE)
                |  sessions <- sessions[valid & !is.na(sessions${'$'}tokens), , drop = FALSE]
                |  totals <- aggregate(tokens ~ id, data = sessions, FUN = sum)
                |  totals${'$'}percent <- round(totals${'$'}tokens / sum(totals${'$'}tokens) * 100, 2)
                |  head(totals[order(-totals${'$'}tokens), ], n = limit)
                |}
                |
                |SessionStore{n} <- setRefClass("SessionStore{n}",
                |  fields = list(items = "list", capacity = "numeric"),
                |  methods = list(
                |    load = function(id) {
                |      url <- sprintf("http://localhost:4096/session/%s?limit=%d", id, as.integer(capacity * 2))
                |      body <- tryCatch(
                |        jsonlite::fromJSON(url),
                |        error = function(e) {
                |          message("load failed: ", conditionMessage(e))
                |          NULL
                |        }
                |      )
                |      if (is.null(body)) return(NULL)
                |      items[[id]] <<- body
                |      if (length(items) > capacity) items[[1]] <<- NULL
                |      body
                |    },
                |    size = function() sum(vapply(items, function(s) s${'$'}tokens > 0x1F, logical(1))) + 1e3
                |  )
                |)
                |
                |describe <- function(status) {
                |  switch(status,
                |    idle = "idle",
                |    busy = paste0("busy since ", format(Sys.time(), "%H:%M:%S")),
                |    stop(sprintf("unknown status '%s'", status))
                |  )
                |}
                |
                |sessions <- data.frame(id = c("ses_1", "ses_2"), tokens = c(120L, 3400L), stringsAsFactors = FALSE)
                |sessions |>
                |  subset(tokens > 100) |>
                |  transform(ratio = tokens / max(tokens), big = tokens >= 1000) %>%
                |  summary()
                |squares <- sapply(1:10, \(x) x^2)
                |note <- r"(raw string with "quotes" and \backslashes)"
                |for (i in seq_len(3)) if (i %% 2 == 0) next else print(c(TRUE, NA, Inf, -1i))
                |
                """.trimMargin(),
            "sas" to
                """
                |/* Session report {n}: sums token counts per session. */
                |%let limit = 64;
                |%let outlib = work;
                |options nodate nonumber ls=120;
                |
                |data &outlib..sessions_{n};
                |    infile datalines dlm=',' dsd truncover;
                |    length id ${'$'}16 title ${'$'}40 status ${'$'}8;
                |    input id ${'$'} title ${'$'} tokens status ${'$'};
                |    if tokens > 1000 then size = 'large';
                |    else if tokens = 0 then delete;
                |    else size = 'normal';
                |    ratio = tokens * 0.75;
                |    format ratio 8.2;
                |    label tokens = "Token count";
                |datalines;
                |ses_1,First session,120,idle
                |ses_2,"Second, quoted",3400,busy
                |ses_3,Third session,0,failed
                |;
                |run;
                |
                |proc sort data=&outlib..sessions_{n} out=sorted;
                |    by descending tokens;
                |run;
                |
                |proc sql noprint;
                |    create table totals as
                |    select status, count(*) as sessions, sum(tokens) as total format=comma12.
                |    from sorted
                |    where id like 'ses_%' and tokens is not missing
                |    group by status
                |    having calculated total > 0
                |    order by total desc;
                |    select count(*) into :nsess trimmed from sorted;
                |quit;
                |
                |%macro report_{n}(ds=, top=10);
                |    %if %sysfunc(exist(&ds)) %then %do;
                |        title "Top &top sessions (&nsess total)";
                |        proc print data=&ds(obs=&top) noobs label;
                |            var id title tokens ratio;
                |        run;
                |    %end;
                |    %else %put WARNING: dataset &ds does not exist.;
                |%mend report_{n};
                |
                |%report_{n}(ds=sorted, top=&limit)
                |
                |* A statement comment ends at the semicolon;
                |proc means data=sorted n mean max maxdec=2;
                |    class status;
                |    var tokens;
                |    output out=stats mean=avg_tokens;
                |run;
                |
                """.trimMargin(),
            "scala" to
                """
                |/** Session store {n}: keeps the latest revision per key. */
                |package dev.opencode.bench.store{n}
                |
                |import scala.collection.mutable
                |import scala.concurrent.{ExecutionContext, Future}
                |
                |enum Status:
                |  case Idle
                |  case Busy(since: Long)
                |  case Failed(message: String, code: Int = 0x1F)
                |
                |final case class Session(id: String, title: String, tokens: Long = 0L)
                |
                |trait SessionLoader[F[_]]:
                |  def fetch(path: String): F[Option[Session]]
                |
                |object SessionStore:
                |  val MaxItems = 64
                |  private[store{n}] val IdPattern = ""${'"'}^ses_[a-z0-9]+${'$'}""${'"'}.r
                |
                |class SessionStore(capacity: Int = SessionStore.MaxItems)(using ec: ExecutionContext):
                |  private val items = mutable.LinkedHashMap.empty[String, Session]
                |  @volatile private var status: Status = Status.Idle
                |
                |  def load(id: String, loader: SessionLoader[Future]): Future[Option[Session]] =
                |    require(SessionStore.IdPattern.matches(id), s"bad id: ${'$'}id")
                |    status = Status.Busy(System.currentTimeMillis())
                |    loader.fetch(s"/session/${'$'}id?limit=${'$'}{capacity * 2}").map {
                |      case Some(session) =>
                |        items.update(id, session)
                |        if items.size > capacity then items.remove(items.head._1)
                |        status = Status.Idle
                |        Some(session)
                |      case None => None
                |    }.recover { case e: java.io.IOException =>
                |      status = Status.Failed(e.getMessage)
                |      None
                |    }
                |
                |  def size: Int = items.values.count(_.tokens % 2 == 0) + 1_000
                |
                |  def describe: String = status match
                |    case Status.Idle => "idle"
                |    case Status.Busy(since) if since > 0 => f"busy since ${'$'}since%d ms (${'$'}{0.75 * 2}%.2f)"
                |    case Status.Failed(message, code) =>
                |      s""${'"'}failed "${'$'}message"
                |         |with code ${'$'}code""${'"'}.stripMargin
                |    case _ => 'x'.toString
                |
                |extension (s: Session) def isLarge: Boolean = s.tokens > 1000L
                |
                |@main def demo{n}(): Unit =
                |  val squares = for x <- 1 to 10 if x % 2 == 0 yield x * x
                |  println(squares.mkString(", "))
                |
                """.trimMargin(),
            "smalltalk" to
                """
                |"Session store {n}: keeps the latest revision per key."
                |Object subclass: #SessionStore{n}
                |    instanceVariableNames: 'items capacity status'
                |    classVariableNames: 'MaxItems'
                |    package: 'Opencode-Sessions'!
                |
                |!SessionStore{n} methodsFor: 'initialization'!
                |initializeWithCapacity: anInteger
                |    "Set up an empty store."
                |    items := OrderedDictionary new.
                |    capacity := anInteger ifNil: [64].
                |    status := #idle! !
                |
                |!SessionStore{n} methodsFor: 'loading'!
                |load: aSessionId
                |    | response session |
                |    (aSessionId beginsWith: 'ses_')
                |        ifFalse: [^ self error: 'bad id: ', aSessionId printString].
                |    status := #busy.
                |    response := [ZnClient new
                |            url: 'http://localhost:4096/session/', aSessionId;
                |            queryAt: 'limit' put: (capacity * 2) printString;
                |            get]
                |        on: ConnectionTimedOut
                |        do: [:ex | Transcript showln: 'load failed: ', ex messageText. ^ nil].
                |    session := STONJSON fromString: response.
                |    items at: aSessionId put: session.
                |    [items size > capacity] whileTrue: [items removeKey: items keys first].
                |    status := #idle.
                |    ^ session! !
                |
                |!SessionStore{n} methodsFor: 'accessing'!
                |size
                |    ^ (items values count: [:each | (each at: #tokens ifAbsent: [0]) > 16r1F]) + 1000!
                |
                |describe
                |    ^ status = #idle
                |        ifTrue: ['idle']
                |        ifFalse: ['busy since ', DateAndTime now printString]! !
                |
                || store squares |
                |store := SessionStore{n} new.
                |squares := (1 to: 10) select: [:x | x even] thenCollect: [:x | x * x].
                |Transcript show: squares printString; cr; show: ${'$'}a asString, #(1 2.5 ${'$'}b #sym 'it''s' true nil) printString; cr.
                |store load: 'ses_1'.
                |(2r1010 + 3.0e-2 + (1/3)) printNl!
                |
                """.trimMargin(),
            "svelte" to
                """
                |<script lang="ts">
                |  // Session list {n}: shows sessions with live token counts.
                |  import { onMount, createEventDispatcher } from 'svelte';
                |  import { fade } from 'svelte/transition';
                |  import type { Session } from './types';
                |
                |  export let sessions: Session[] = [];
                |  export let limit = 64;
                |
                |  const dispatch = createEventDispatcher<{ select: string }>();
                |  let filter = '';
                |  let loading{n} = false;
                |
                |  ${'$'}: visible = sessions
                |    .filter((s) => s.title.toLowerCase().includes(filter.toLowerCase()))
                |    .slice(0, limit);
                |  ${'$'}: total = visible.reduce((sum, s) => sum + s.tokens, 0);
                |
                |  onMount(async () => {
                |    loading{n} = true;
                |    const response = await fetch(`/session?limit=${'$'}{limit * 2}`);
                |    sessions = (await response.json()) as Session[];
                |    loading{n} = false;
                |  });
                |</script>
                |
                |<!-- Session list with a filter box -->
                |<section class="sessions" class:empty={visible.length === 0}>
                |  <input type="search" placeholder="Filter sessions" bind:value={filter} />
                |  {#if loading{n}}
                |    <p class="hint">Loading&hellip;</p>
                |  {:else if visible.length === 0}
                |    <p class="hint">No sessions match "{filter}"</p>
                |  {:else}
                |    <ul>
                |      {#each visible as session, index (session.id)}
                |        <li transition:fade={{ duration: 150 }} on:click={() => dispatch('select', session.id)}>
                |          <strong>{index + 1}. {session.title}</strong>
                |          <span>{session.tokens.toLocaleString()} tokens</span>
                |          {#if session.tokens > 0x1f}<em>large</em>{/if}
                |          {@html session.preview ?? ''}
                |        </li>
                |      {/each}
                |    </ul>
                |    <p>Total: {total} ({(total / Math.max(1, visible.length)).toFixed(2)} per session)</p>
                |  {/if}
                |  {#await fetch('/health') then health}
                |    <small>server {health.ok ? 'up' : 'down'}</small>
                |  {:catch error}
                |    <small class="error">{error.message}</small>
                |  {/await}
                |</section>
                |
                |<style lang="scss">
                |  .sessions {
                |    display: grid;
                |    gap: 0.5rem;
                |    &.empty { opacity: 0.6; }
                |  }
                |  li:hover strong { color: #3b82f6; }
                |</style>
                |
                """.trimMargin(),
            "vb" to
                """
                |' Session store {n}: keeps the latest revision per key.
                |Option Strict On
                |
                |Imports System.Net.Http
                |Imports System.Text.RegularExpressions
                |
                |Namespace Opencode.Sessions{n}
                |
                |    Public Enum Status
                |        Idle
                |        Busy
                |        Failed = &H1F
                |    End Enum
                |
                |    ''' <summary>A bounded session store.</summary>
                |    Public Class SessionStore
                |        Private Const MaxItems As Integer = 64
                |        Private Shared ReadOnly IdPattern As New Regex("^ses_[a-z0-9]+${'$'}", RegexOptions.IgnoreCase)
                |        Private ReadOnly _items As New Dictionary(Of String, Session)()
                |        Private ReadOnly _client As New HttpClient()
                |
                |        Public Property Capacity As Integer = MaxItems
                |        Public Property CurrentStatus As Status = Status.Idle
                |
                |        Public Async Function LoadAsync(id As String) As Task(Of Session)
                |            If Not IdPattern.IsMatch(id) Then
                |                Throw New ArgumentException(${'$'}"bad id: {id}", NameOf(id))
                |            End If
                |            CurrentStatus = Status.Busy
                |            Try
                |                Dim body = Await _client.GetStringAsync(${'$'}"http://localhost:4096/session/{id}?limit={Capacity * 2}")
                |                Dim session = Text.Json.JsonSerializer.Deserialize(Of Session)(body)
                |                _items(id) = session
                |                If _items.Count > Capacity Then _items.Remove(_items.Keys.First())
                |                Return session
                |            Catch ex As HttpRequestException When ex.StatusCode.HasValue
                |                Return Nothing
                |            Finally
                |                CurrentStatus = Status.Idle
                |            End Try
                |        End Function
                |
                |        Public ReadOnly Property Size As Integer
                |            Get
                |                Return _items.Values.Where(Function(s) s.Tokens Mod 2 = 0).Count() + 1000
                |            End Get
                |        End Property
                |
                |        Public Function Describe() As String
                |            Select Case CurrentStatus
                |                Case Status.Idle : Return "idle"
                |                Case Status.Busy : Return String.Format("busy since {0:HH:mm} ""quoted""${'"'}, DateTime.Now)
                |                Case Else : Return "failed"
                |            End Select
                |        End Function
                |    End Class
                |
                |    Module Demo{n}
                |        Sub Main()
                |            Dim squares = From x In Enumerable.Range(1, 10) Where x Mod 2 = 0 Select x * x
                |            Dim ratio As Double = 0.75R, flag As Boolean = True, ch As Char = "x"c
                |            For Each value In squares : Console.WriteLine(value) : Next
                |        End Sub
                |    End Module
                |
                |End Namespace ' Opencode.Sessions{n}
                |
                """.trimMargin(),
            "vue" to
                """
                |<template>
                |  <!-- Session list {n}: shows sessions with live token counts. -->
                |  <section class="sessions" :class="{ empty: visible.length === 0 }">
                |    <input v-model.trim="filter" type="search" placeholder="Filter sessions" />
                |    <p v-if="loading" class="hint">Loading&hellip;</p>
                |    <p v-else-if="visible.length === 0" class="hint">No sessions match "{{ filter }}"</p>
                |    <ul v-else>
                |      <li
                |        v-for="(session, index) in visible"
                |        :key="session.id"
                |        :title="`#${'$'}{index + 1}: ${'$'}{session.title}`"
                |        @click="emit('select', session.id)"
                |      >
                |        <strong>{{ session.title }}</strong>
                |        <span>{{ session.tokens.toLocaleString() }} tokens</span>
                |        <em v-show="session.tokens > 0x1f">large</em>
                |      </li>
                |    </ul>
                |    <slot name="footer" :total="total">Total: {{ total }}</slot>
                |  </section>
                |</template>
                |
                |<script setup lang="ts">
                |import { computed, onMounted, ref } from 'vue';
                |import type { Session } from './types';
                |
                |const props = withDefaults(defineProps<{ sessions?: Session[]; limit?: number }>(), {
                |  sessions: () => [],
                |  limit: 64,
                |});
                |const emit = defineEmits<{ (e: 'select', id: string): void }>();
                |
                |const filter = ref('');
                |const loading = ref(false);
                |const loaded{n} = ref<Session[]>([]);
                |
                |const visible = computed(() =>
                |  [...props.sessions, ...loaded{n}.value]
                |    .filter((s) => s.title.toLowerCase().includes(filter.value.toLowerCase()))
                |    .slice(0, props.limit),
                |);
                |const total = computed(() => visible.value.reduce((sum, s) => sum + s.tokens, 0));
                |
                |onMounted(async () => {
                |  loading.value = true;
                |  try {
                |    const response = await fetch(`/session?limit=${'$'}{props.limit * 2}`);
                |    loaded{n}.value = (await response.json()) as Session[];
                |  } catch (error) {
                |    console.error('load failed', error);
                |  } finally {
                |    loading.value = false;
                |  }
                |});
                |</script>
                |
                |<style scoped lang="scss">
                |.sessions {
                |  display: grid;
                |  gap: 0.5rem;
                |  &.empty { opacity: 0.6; }
                |}
                |li:hover strong { color: #3b82f6; }
                |</style>
                |
                """.trimMargin(),
            "zig" to
                """
                |//! Session store {n}: keeps the latest revision per key.
                |const std = @import("std");
                |const Allocator = std.mem.Allocator;
                |
                |pub const Status = enum { idle, busy, failed };
                |
                |pub const Session = struct {
                |    id: []const u8,
                |    title: []const u8,
                |    tokens: u64 = 0,
                |};
                |
                |const max_items: usize = 64;
                |
                |pub const LoadError = error{ BadId, NotFound } || Allocator.Error;
                |
                |/// A bounded session store.
                |pub fn SessionStore{n}(comptime T: type) type {
                |    return struct {
                |        const Self = @This();
                |
                |        items: std.StringArrayHashMap(T),
                |        capacity: usize = max_items,
                |        status: Status = .idle,
                |
                |        pub fn init(allocator: Allocator) Self {
                |            return .{ .items = std.StringArrayHashMap(T).init(allocator) };
                |        }
                |
                |        pub fn deinit(self: *Self) void {
                |            self.items.deinit();
                |        }
                |
                |        pub fn put(self: *Self, id: []const u8, item: T) LoadError!void {
                |            if (!std.mem.startsWith(u8, id, "ses_")) return error.BadId;
                |            self.status = .busy;
                |            defer self.status = .idle;
                |            try self.items.put(id, item);
                |            while (self.items.count() > self.capacity) {
                |                _ = self.items.orderedRemove(self.items.keys()[0]);
                |            }
                |        }
                |
                |        pub fn size(self: Self) usize {
                |            var count: usize = 1_000;
                |            for (self.items.values()) |item| {
                |                if (item.tokens > 0x1F) count += 1;
                |            }
                |            return count;
                |        }
                |    };
                |}
                |
                |test "store {n} evicts the oldest session" {
                |    var store = SessionStore{n}(Session).init(std.testing.allocator);
                |    defer store.deinit();
                |    try store.put("ses_1", .{ .id = "ses_1", .title = "First", .tokens = 120 });
                |    const note =
                |        \\multi-line string literal
                |        \\with "quotes" and \n no escapes
                |    ;
                |    const ratio: f64 = 0.75 * 2.0;
                |    std.debug.print("{s} {d:.2} {c}\n", .{ note, ratio, 'x' });
                |    try std.testing.expectError(error.BadId, store.put("bad", .{ .id = "bad", .title = "" }));
                |}
                |
                """.trimMargin(),
        )
}
