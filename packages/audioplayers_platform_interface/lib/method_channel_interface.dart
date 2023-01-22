import 'package:flutter/services.dart';

extension StandardMethodChannel on MethodChannel {
  Future<void> call(String method, Map<String, dynamic> args) async {
    return invokeMethod<void>(method, args);
  }

  Future<T?> compute<T>(String method, Map<String, dynamic> args) async {
    return invokeMethod<T>(method, args);
  }
}

extension StandardMethodCall on MethodCall {
  Map<dynamic, dynamic> get args => arguments as Map<dynamic, dynamic>;

  bool containsKey(String key) => args.containsKey(key);

  String getString(String key) {
    return args[key] as String;
  }

  int getInt(String key) {
    return args[key] as int;
  }

  bool getBool(String key) {
    return args[key] as bool;
  }
}

extension MapParser on Map<dynamic, dynamic> {
  bool containsKey(String key) => this.containsKey(key);

  String getString(String key) {
    return this[key] as String;
  }

  int getInt(String key) {
    return this[key] as int;
  }

  bool getBool(String key) {
    return this[key] as bool;
  }
}
