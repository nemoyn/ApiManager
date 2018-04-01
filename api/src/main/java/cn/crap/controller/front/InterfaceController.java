package cn.crap.controller.front;

import cn.crap.adapter.InterfaceAdapter;
import cn.crap.dto.InterfaceDto;
import cn.crap.dto.InterfacePDFDto;
import cn.crap.framework.JsonResult;
import cn.crap.framework.MyException;
import cn.crap.framework.ThreadContext;
import cn.crap.framework.base.BaseController;
import cn.crap.model.mybatis.*;
import cn.crap.service.custom.CustomInterfaceService;
import cn.crap.service.mybatis.InterfaceService;
import cn.crap.service.mybatis.ModuleService;
import cn.crap.beans.Config;
import cn.crap.utils.*;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller("frontInterfaceController")
@RequestMapping("/front/interface")
public class InterfaceController extends BaseController {

    @Autowired
    private InterfaceService interfaceService;
    @Autowired
    private CustomInterfaceService customInterfaceService;
    @Autowired
    private ModuleService moduleService;
    @Autowired
    private Config config;

    private final static String ERROR_INTERFACE_ID = "接口id有误，生成PDF失败。请确认配置文件config.properties中的网站域名配置是否正确！";
    private final static String ERROR_MODULE_ID = "模块id有误，生成PDF失败。请确认配置文件config.properties中的网站域名配置是否正确！";

    /***
     *  下载时调用该接口生成html，然后转换成pdf下载
     * @param id
     * @param moduleId
     * @param secretKey
     * @return
     * @throws Exception
     */
    @RequestMapping("/detail/pdf.do")
    public String pdf(String id, String moduleId, @RequestParam String secretKey) throws Exception {
        try {
            HttpServletRequest request = ThreadContext.request();
            if (MyString.isEmpty(id) && MyString.isEmpty(moduleId)){
                request.setAttribute("result", "接口ID&模块ID不能同时为空！");
                return ERROR_VIEW;
            }
            if (!secretKey.equals(settingCache.get(IConst.C_SETTING_SECRETKEY).getValue())) {
                request.setAttribute("result", "秘钥不正确，非法请求！");
                return ERROR_VIEW;
            }

            if (MyString.isEmpty(id) && MyString.isEmpty(moduleId)) {
                request.setAttribute("result", "参数不能为空，生成PDF失败！");
                return ERROR_VIEW;
            }

            List<InterfacePDFDto> interfacePDFDtos = new ArrayList<>();
            request.setAttribute("MAIN_COLOR", settingCache.get("MAIN_COLOR").getValue());
            request.setAttribute("ADORN_COLOR", settingCache.get("ADORN_COLOR").getValue());
            request.setAttribute("title", settingCache.get("TITLE").getValue());

            /**
             * 单个生成pdf接口
             */
            if (!MyString.isEmpty(id)) {
                InterfaceWithBLOBs interFace = interfaceService.getById(id);
                if (interFace == null) {
                    request.setAttribute("result", ERROR_INTERFACE_ID);
                    return ERROR_VIEW;
                }
                Module module = moduleService.getById(interFace.getModuleId());
                interfacePDFDtos.add(customInterfaceService.getInterDto(interFace));
                request.setAttribute("interfaces", interfacePDFDtos);
                request.setAttribute("moduleName", module.getName());
                return ERROR_VIEW;
            }

            /**
             * 按模块批量生成pdf接口
             */
            Module module = moduleService.getById(moduleId);
            if (module == null) {
                request.setAttribute("result", ERROR_MODULE_ID);
                return ERROR_VIEW;
            }
            for (InterfaceWithBLOBs interFace : customInterfaceService.selectByModuleId(moduleId)) {
                interfacePDFDtos.add(customInterfaceService.getInterDto(interFace));
            }

            request.setAttribute("interfaces", interfacePDFDtos);
            request.setAttribute("moduleName", module.getName());
            return "/WEB-INF/views/interFacePdf.jsp";
        } catch (Exception e) {
            ThreadContext.request().setAttribute("result", "接口数据有误，请修改接口后再试，错误信息：" + e.getMessage());
            return ERROR_VIEW;
        }
    }

    @RequestMapping("/download/pdf.do")
    @ResponseBody
    public void download(String id, String moduleId, HttpServletRequest req, HttpServletResponse response) throws Exception {

        Module module = null;
        if (!MyString.isEmpty(moduleId)) {
            module = moduleCache.get(moduleId);
        } else {
            module = moduleCache.get(interfaceService.getById(id).getModuleId());
        }

        Project project = projectCache.get(module.getProjectId());
        // 如果是私有项目，必须登录才能访问，公开项目需要查看是否需要密码

        // 使用缓存的密码，不需要验证码
        checkFrontPermission("", "", project);

        String displayFilename = "CrapApi" + System.currentTimeMillis() + ".pdf";
        byte[] buf = new byte[1024 * 1024 * 10];
        int len = 0;
        ServletOutputStream ut = null;
        BufferedInputStream br = null;
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "must-revalidate, no-transform");
        response.setDateHeader("Expires", 0L);

        String userAgent = req.getHeader("User-Agent");
        boolean isIE = (userAgent != null) && (userAgent.toLowerCase().indexOf("msie") != -1);
        response.setContentType("application/x-download");
        if (isIE) {
            displayFilename = URLEncoder.encode(displayFilename, "UTF-8");
            response.setHeader("Content-Disposition", "attachment;filename=\"" + displayFilename + "\"");
        } else {
            displayFilename = new String(displayFilename.getBytes("UTF-8"), "ISO8859-1");
            response.setHeader("Content-Disposition", "attachment;filename=" + displayFilename);
        }
        String secretKey = settingCache.get(IConst.C_SETTING_SECRETKEY).getValue();
        br = new BufferedInputStream(new FileInputStream(Html2Pdf.createPdf(req, config, id, moduleId, secretKey)));
        ut = response.getOutputStream();
        while ((len = br.read(buf)) != -1)
            ut.write(buf, 0, len);
        ut.flush();
        br.close();
    }


    @RequestMapping("/list.do")
    @ResponseBody
    public JsonResult webList(@RequestParam String moduleId, String interfaceName, String url,
                               Integer currentPage, String password, String visitCode) throws MyException {
        if (MyString.isEmpty(moduleId)) {
            throw new MyException("000020");
        }

        Module module = moduleService.getById(moduleId);
        Project project = projectCache.get(module.getProjectId());
        // 如果是私有项目，必须登录才能访问，公开项目需要查看是否需要密码
        checkFrontPermission(password, visitCode, project);

        Page page = new Page(currentPage);

        InterfaceCriteria example = new InterfaceCriteria();
        InterfaceCriteria.Criteria criteria = example.createCriteria().andModuleIdEqualTo(moduleId);
        if (!MyString.isEmpty(interfaceName)) {
            criteria.andInterfaceNameLike("%" + interfaceName + "%");
        }
        if (!MyString.isEmpty(url)) {
            criteria.andFullUrlLike("%" + url + "%");
        }

        List<InterfaceDto> interfaces = InterfaceAdapter.getDto(interfaceService.selectByExampleWithBLOBs(example), module);

        return new JsonResult(1, interfaces, page,
                Tools.getMap("crumbs", Tools.getCrumbs(projectCache.get(module.getProjectId()).getName(), "#/" + module.getProjectId() + "/module/list", module.getName(), "void")));
    }

    @RequestMapping("/detail.do")
    @ResponseBody
    public JsonResult webDetail(@ModelAttribute InterfaceWithBLOBs interFace, String password, String visitCode) throws MyException {
        interFace = interfaceService.getById(interFace.getId());
        if (interFace != null) {
            Module module = moduleCache.get(interFace.getModuleId());
            Project project = projectCache.get(interFace.getProjectId());
            // 如果是私有项目，必须登录才能访问，公开项目需要查看是否需要密码
            checkFrontPermission(password, visitCode, project);

            /**
             * 查询相同模块下，相同接口名的其它版本号
             */
            InterfaceCriteria example = new InterfaceCriteria();
            InterfaceCriteria.Criteria criteria = example.createCriteria().andModuleIdEqualTo(interFace.getModuleId())
                    .andInterfaceNameEqualTo(interFace.getInterfaceName()).andVersionNotEqualTo(interFace.getVersion());

            List<InterfaceDto> versions = InterfaceAdapter.getDto(interfaceService.selectByExampleWithBLOBs(example), module);

            return new JsonResult(1, InterfaceAdapter.getDto(interFace, module), null,
                    Tools.getMap("versions", versions,
                            "crumbs",
                            Tools.getCrumbs(
                                    project.getName(), "#/" + project.getId() + "/module/list",
                                    module.getName() + ":接口列表", "#/" + project.getId() + "/interface/list/" + module.getId(),
                                    interFace.getInterfaceName(), "void"), "module", moduleCache.get(interFace.getModuleId())));
        } else {
            throw new MyException(E000012);
        }
    }

    @RequestMapping("/debug.do")
    @ResponseBody
    public JsonResult debug(@RequestParam String params, @RequestParam String headers, @RequestParam(defaultValue = "") String customParams,
                            @RequestParam String debugMethod, @RequestParam String fullUrl) throws Exception {

        JSONArray jsonParams = JSONArray.fromObject(params);
        JSONArray jsonHeaders = JSONArray.fromObject(headers);
        Map<String, String> httpParams = new HashMap<String, String>();
        for (int i = 0; i < jsonParams.size(); i++) {
            JSONObject param = jsonParams.getJSONObject(i);
            for (Object paramKey : param.keySet()) {
                if (fullUrl.contains("{" + paramKey.toString() + "}")) {
                    fullUrl = fullUrl.replace("{" + paramKey.toString() + "}", param.getString(paramKey.toString()));
                } else {
                    httpParams.put(paramKey.toString(), param.getString(paramKey.toString()));
                }

            }
        }

        Map<String, String> httpHeaders = new HashMap<String, String>();
        for (int i = 0; i < jsonHeaders.size(); i++) {
            JSONObject param = jsonHeaders.getJSONObject(i);
            for (Object paramKey : param.keySet()) {
                httpHeaders.put(paramKey.toString(), param.getString(paramKey.toString()));
            }
        }
        // 如果自定义参数不为空，则表示需要使用post发送自定义包体
        if (!MyString.isEmpty(customParams)) {
            return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.postBody(fullUrl, customParams, httpHeaders)));
        }

        try {
            switch (debugMethod) {
                case "POST":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.post(fullUrl, httpParams, httpHeaders)));
                case "GET":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.get(fullUrl, httpParams, httpHeaders)));
                case "PUT":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.put(fullUrl, httpParams, httpHeaders)));
                case "DELETE":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.delete(fullUrl, httpParams, httpHeaders)));
                case "HEAD":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.head(fullUrl, httpParams, httpHeaders)));
                case "OPTIONS":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.options(fullUrl, httpParams, httpHeaders)));
                case "TRACE":
                    return new JsonResult(1, Tools.getMap("debugResult", HttpPostGet.trace(fullUrl, httpParams, httpHeaders)));
                default:
                    return new JsonResult(1, Tools.getMap("debugResult", "不支持的请求方法：" + debugMethod));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new JsonResult(1, Tools.getMap("debugResult", "调试出错\r\nmessage:" + e.getMessage()));
        }
    }

}
