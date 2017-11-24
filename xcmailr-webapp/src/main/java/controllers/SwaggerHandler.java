/**  
 *  Copyright 2017 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *
 */
package controllers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import filters.NoLoginFilter;
import ninja.FilterWith;
import ninja.Result;
import ninja.Results;
import ninja.Route;
import ninja.Router;
import ninja.params.PathParam;

/**
 * Handles swagger generation
 * 
 * @author Patrick Thum, Apinauten GmbH, Germany
 */
@Singleton
public class SwaggerHandler
{
    @Inject
    Logger log;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Router router;

    /**
     * Opens the empty delete-box-dialog (just rendering the template)<br/>
     * GET /apispec/swagger.json
     * 
     * @return the Delete-Box-Dialog
     */
    @FilterWith(NoLoginFilter.class)
    public Result getSwaggerJson()
    {
        JSONObject result = new JSONObject();
        final JSONObject arr = new JSONObject();
        try
        {
            for (Route route : router.getRoutes())
            {
                final JSONObject pathObj = new JSONObject();
                String method = route.getHttpMethod();
                String path = route.getUrl();
                final JSONObject methodObj = new JSONObject();
                methodObj.put("description", "");

                final Method ctrlMethod = route.getControllerMethod();
                final JSONArray paramArray = new JSONArray();

                for (Parameter param : ctrlMethod.getParameters())
                {
                    final JSONObject paramObj = new JSONObject();
                    Annotation[] annotations = param.getAnnotations();
                    if (annotations != null && annotations.length > 0)
                    {
                        for (Annotation ann : annotations)
                        {
                            if (PathParam.class.equals(ann.getClass()))
                            {
                                paramObj.put("in", "path");
                            }
                            // FIXME, it is currently not possible to determine other types clearly...
                        }
                    }
                }
                methodObj.put("operationId", ctrlMethod.getName());
                methodObj.put("summary", "");
                JSONArray tagArray = new JSONArray();
                tagArray.put(route.getControllerClass().getName());
                methodObj.put("tags", tagArray);
                pathObj.put(method.toLowerCase(), methodObj);
                arr.put(path, pathObj);
            }
            result.put("paths", arr);
        }
        catch (JSONException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return Results.json().renderRaw(result.toString().getBytes());
    }

    /**
     * Opens the empty delete-box-dialog (just rendering the template)<br/>
     * GET /apispec/index.html
     * 
     * @return the Delete-Box-Dialog
     */
    @FilterWith(NoLoginFilter.class)
    public Result getSwaggerHtml()
    {
        return Results.html();
    }

}
