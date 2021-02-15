package by.agat.system.controller;

import by.agat.system.domain.*;
import by.agat.system.repository.DocumentRepository;
import by.agat.system.services.DocumentService;
import by.agat.system.services.DocumentStatusService;
import by.agat.system.services.UserService;
import by.agat.system.utility.Converter;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/inspector")
@PropertySource(value = {"classpath:application.properties"}, encoding = "windows-1251")
public class InspectorController {

    @Value("${pathFileUploadServer}")
    private String pathToUpload;

    @Autowired
    private UserService userService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentStatusService documentStatusService;

    private static final Logger log = Logger.getLogger(InspectorController.class);


    @GetMapping()
    public ModelAndView inspectorPage(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        int docSizeStatus1 = 0;
        int docSizeStatus2 = 0;
        for (int i=0; i<user.getDocuments().size(); i++){
            int status = user.getDocuments().get(i).getStatuses().size();
            switch (status){
                case 1: docSizeStatus1++; break;
                case 2: docSizeStatus2++; break;
                default: docSizeStatus1=-1; docSizeStatus2=-1; break;
            }
        }
        int codeInspector = user.getOrgForBank().getItsNumber();
        int docSizeStatus3 = documentService.getDocumentsByOrgcode2AndStatusAndType(codeInspector, 3, 2).size();
        model.addObject("docSize1", docSizeStatus1);
        model.addObject("docSize2", docSizeStatus2);
        model.addObject("docSize3", docSizeStatus3);
        model.addObject("user", user);
        model.setViewName("inspector/index");
        return model;
    }

    @GetMapping("/uploadfiles")
    public ModelAndView uploadFilePage(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        model.addObject("user", user);
        model.addObject("document", new UploadDocument());
        model.setViewName("inspector/uploadfile");
        return model;
    }

    @GetMapping("/getUploadFiles")
    public @ResponseBody
    List<DocumentDTO> getUploadFiles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        SimpleDateFormat sd = new SimpleDateFormat("yyyy.MM.dd");
        List<DocumentDTO> documents = new ArrayList<>();
        List<Document> uploadToday = user.getDocuments().stream()
                .filter(document -> (document.getStatuses().get(document.getStatuses().size() - 1).getStatus().getId() == 1
                        || document.getStatuses().get(document.getStatuses().size() - 1).getStatus().getId() == 2) &&
                        (document.getType().getType_id() == 1) &&
                        sd.format(document.getDateUpload()).equals(sd.format(new Date())))
                .collect(Collectors.toList());
        for (Document document : uploadToday) {
            DocumentDTO documentDTO = Converter.convertFromDocumentToDTO(document);
            documents.add(documentDTO);
        }
        return documents;
    }


    @PostMapping("/uploadSgn")
    public @ResponseBody
    String uploadSgn(@RequestParam("file") MultipartFile file, @RequestParam("path") String pathFile) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        byte[] bytes = new byte[0];
        try {
            bytes = file.getBytes();
            String pathFileEnd = createDirectory(pathFile.substring(0,23));
            Path path = Paths.get(pathFileEnd + "/" + pathFile);
            Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pathFileEnd + "/" + pathFile), "UTF-8"));

//            Path path = Paths.get(pathToUpload + pathFile);
//            Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pathToUpload + pathFile), "UTF-8"));
            try {
                out.write(Arrays.toString(bytes));
            } finally {
                out.close();
            }
            Files.write(path, bytes);
            log.info("Инспектор " + user.getUsername() + " успешно подписал документ: " + pathFile);
            return "SUCCESS";
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Инспектор " + user.getUsername() + " НЕУСПЕШНО подписал документ: " + pathFile);
            return e.getMessage();
        }

    }



    @PostMapping("/loadDocument")
    public @ResponseBody
    String fileUpload(@RequestParam("file") MultipartFile[] files) {
        String newPAth = pathToUpload;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        String nameFile = "";
        try {
            for (MultipartFile file : files) {

                String originalName = file.getName();
                String delimeter = "\\\\";
                String arrayPath[] = file.getOriginalFilename().split(delimeter);
                String codeBankStr = "";
                Document doc = new Document();


                nameFile = arrayPath[arrayPath.length - 1];
                String pathFileEnd = createDirectory(nameFile);

                codeBankStr = nameFile.substring(4, 15);
                doc.setName(nameFile);
                doc.setPath(pathFileEnd + "/" + nameFile);
                TypeDocument type = new TypeDocument();
                type.setType_id(1);
                doc.setType(type);
                doc.setUser(user);
                doc.setOrgcode(codeBankStr);
                doc.setDateUpload(new Date());
                byte[] bytes = file.getBytes();
                Path pathForDownload = Paths.get(pathFileEnd + "/" + nameFile);
                Files.write(pathForDownload, bytes);
                documentService.save(doc);
                DocumentStatus docStatus = new DocumentStatus();
                Status status = new Status();
                status.setId(1);
                docStatus.setStatus(status);
                docStatus.setDateChange(new Date());
                docStatus.setDocument(doc);
                docStatus.setUser(user);
                documentStatusService.save(docStatus);
                log.info("Инспектор " + user.getUsername() + " успешно загрузил документ: " + nameFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Ошибка загрузки документа у инспектора " + user.getUsername() + ", "+ "документ: " + nameFile + " Ошибка: " + e.getMessage());
            return e.getMessage();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Ошибка загрузки документа у инспектора " + user.getUsername() + ", "+ "документ: " + nameFile + " Ошибка: " + e.getMessage());
            return e.getMessage();
        }
        return "OK";
    }

    public String createDirectory(String nameFile) throws IOException {
        String newPAth = pathToUpload;
        String month = nameFile.substring(16,18);
        String year = "20" + nameFile.substring(18,20);
        String period = nameFile.substring(22,23);
        String pathFileEnd = newPAth + year + "_" + month + "_" + period;
        Path path = Paths.get(pathFileEnd);

//        if (!Files.exists(Paths.get(newPAth+ year))){
//            Files.createDirectory(Paths.get(newPAth+ year));
//            if (!Files.exists(Paths.get(newPAth+ year + "/" + month))){
//                Files.createDirectory(Paths.get(newPAth+ year + "/" + month));
//                if (!Files.exists(Paths.get(newPAth+ year + "/" + month + "/" + period))){
//                    Files.createDirectory(Paths.get(newPAth+ year + "/" + month + "/" + period));
//                }
//            }
//        }
        try {
            if (!Files.exists(Paths.get(pathFileEnd))) {
                Files.createDirectory(Paths.get(pathFileEnd));
            }
        } catch (IOException e){
            e.printStackTrace();
            log.error("Ошибка создания директории документа у инспектора " + "документ: " + nameFile + " Ошибка: " + e.getMessage());
            return e.getMessage();
        }
        return pathFileEnd;
    }





//    ------------downloadFile------------


    @GetMapping("/downloadFiles")
    public ModelAndView downloadFile(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        model.addObject("user", user);
        model.addObject("document", new UploadDocument());
        model.setViewName("inspector/downloadFile");
        return model;
    }


    @GetMapping("/getUploadFilesReturn")
    public @ResponseBody
    List<DocumentDTO> getUploadFilesByInspector(@RequestParam("status") int status, @RequestParam("typeId") int typeId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        int codeInspector = user.getOrgForBank().getItsNumber();
        List<Document> docs = documentService.getDocumentsByOrgcode2AndStatusAndType(codeInspector, status, typeId);
        List<DocumentDTO> list = docs.stream().map(document ->
                Converter.convertFromDocumentToDTO(document)).collect(Collectors.toList());
        return list;
    }

//    @GetMapping({"/table2InspectorMainPage", ""})
//    public @ResponseBody
//    List<DocumentDTO> getUploadFilesByInspector(@RequestParam("status") int status, @RequestParam("typeId") int typeId) {
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
//        User user = userService.findByUsername(userDetails.getUsername());
//        String codeBank = user.getOrgForBank().getBic();
//        List<Document> docs = documentService.getDocumentsByOrgcodeAndStatusAndType(codeBank, status, typeId);
//        List<DocumentDTO> list = docs.stream().map(document ->
//                Converter.convertFromDocumentToDTO(document)).collect(Collectors.toList());
//        return list;
//    }

    @GetMapping("/tableInspectorMainPage")
    public @ResponseBody
        List<DocumentDTO> getAllDocument() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        List<DocumentDTO> list = user.getDocuments().stream().map(document ->
                Converter.convertFromDocumentToDTO(document)).collect(Collectors.toList());
        return list;
    }

    @PostMapping ("/errorSign")
    public void errorSign(@RequestParam("codeMes") int codeMes, @RequestParam("nameFile") String nameFile) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        switch (codeMes) {
            case 1:
                log.info("Инспектор " + user.getUsername() + " успешно проверил подпись документа: " + nameFile);
                break;
            case 0:
                log.error("Инспектор " + user.getUsername() + " ошибка проверки подписи документа: " + nameFile);
                break;

        }
    }



    @RequestMapping(value = "/downloadZip", method = RequestMethod.GET)
    public void downloadFile(@RequestParam("files[]") List<Integer> files, HttpServletResponse response) throws IOException  {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        List<String> fileNames = new ArrayList<>();
        for (Integer uuid:files) {
            Long uuidLong = Long.valueOf(uuid);
            Document document = documentService.getDocumentByUUID(uuidLong);
            File file = getFile(document.getPath());
            fileNames.add(document.getPath());
            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));
            DocumentStatus documentStatus = new DocumentStatus();
            documentStatus.setUser(user);
            documentStatus.setDocument(document);
            documentStatus.setDateChange(new Date());
            Status status = new Status();
            status.setId(3);
            documentStatus.setStatus(status);
            documentStatusService.save(documentStatus);
            log.info("Банк " + user.getUsername() + " подготовил к загрузке документ: " + document.getName());
        }

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment;filename=\"download.zip\"");

        try (ZipOutputStream zippedOut = new ZipOutputStream(response.getOutputStream())) {
            for (String file : fileNames) {
                FileSystemResource resource = new FileSystemResource(file);
                ZipEntry e = new ZipEntry(resource.getFilename());
                e.setSize(resource.contentLength());
                e.setTime(System.currentTimeMillis());
                zippedOut.putNextEntry(e);
                IOUtils.copy(resource.getInputStream(), zippedOut);
                zippedOut.closeEntry();
            }
            log.info(user.getUsername() + " загрузил архив подготовленных документов " );
        } catch (Exception e) {
            e.printStackTrace();
            log.error(user.getUsername() + " ошибка загрузки архива документов " );
        }
    }

    private File getFile(String filepath) throws FileNotFoundException {
        File file = new File(filepath);
        if (!file.exists()){
            throw new FileNotFoundException("file with path: " + filepath + " was not found.");
        }
        return file;
    }

}
