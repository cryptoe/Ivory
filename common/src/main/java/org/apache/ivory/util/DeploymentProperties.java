/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ivory.util;

import org.apache.ivory.IvoryException;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public class DeploymentProperties extends ApplicationProperties {
    private static final String PROPERTY_FILE = "deploy.properties";

    private static final AtomicReference<DeploymentProperties> instance =
        new AtomicReference<DeploymentProperties>();

    private DeploymentProperties() throws IvoryException {
      super();
    }

    @Override
    protected String getPropertyFile() {
      return PROPERTY_FILE;
    }

    public static Properties get() {
      try {
        if (instance.get() == null) {
          instance.compareAndSet(null, new DeploymentProperties());
        }
        return instance.get();
      } catch (IvoryException e) {
        throw new RuntimeException("Unable to read application " +
            "startup properties", e);
      }
    }
}
