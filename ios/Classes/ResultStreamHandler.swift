//
//  ResultStreamHandler.swift
//  ultralytics_yolo
//
//  Created by Sergio Sánchez on 9/11/23.
//

import Flutter

public class ResultStreamHandler: NSObject, FlutterStreamHandler {
  private var eventSink: FlutterEventSink?

  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    eventSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  public func on(predictions: [[String: Any]]) {
    eventSink?(predictions)
  }
}
