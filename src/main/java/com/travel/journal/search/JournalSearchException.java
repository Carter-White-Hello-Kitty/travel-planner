// Sida Xue — Travel Journal Search
// 本次搜索模块新增：Elasticsearch 调用异常。
package com.travel.journal.search;

public class JournalSearchException extends RuntimeException {
  public JournalSearchException(String message, Throwable cause) { super(message, cause); }
  public JournalSearchException(String message) { super(message); }
}
