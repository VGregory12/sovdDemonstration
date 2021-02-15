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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import javax.persistence.criteria.CriteriaBuilder;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/bank")
public class BankController {

    @Value("${pathFileUploadServerReturn}")
    private String pathToUpload;

    @Value("${pathFileClaimUploadServer}")
    private String getPathToUploadClaim;

    @Autowired
    private UserService userService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentStatusService documentStatusService;

    private static final Logger log = Logger.getLogger(BankController.class);

    @GetMapping({"/", ""})
    public ModelAndView bankPage(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        long docLoad = user.getStatuses().stream().filter(doc -> doc.getStatus().getId() == 3).count();
        long docReturn = user.getDocuments().stream().filter(doc -> doc.getType().getType_id() == 2).count();
        model.addObject("docLoad", docLoad);
        model.addObject("docReturn", docReturn);
        model.addObject("user", userDetails);
        model.setViewName("bank/index");
        return model;
    }


    @GetMapping("/download")
    public ModelAndView uploadFilePage(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        model.addObject("user", user);
        model.setViewName("bank/getPay");
        return model;
    }

    @GetMapping("/getMainUploadFilesReturn")
    public @ResponseBody
    List<DocumentDTO> getAllDocument(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        List<DocumentDTO> list = user.getDocuments().stream().map(document ->
                Converter.convertFromDocumentToDTO(document)).collect(Collectors.toList());
        return list;
    }

    @GetMapping("/getUploadFiles")
    public @ResponseBody
    List<DocumentDTO> getUploadFilesByBank(@RequestParam("status") int status, @RequestParam("typeId") int typeId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        String codeBank = user.getOrgForBank().getBic();
        List<Document> docs = documentService.getDocumentsByOrgcodeAndStatusAndType(codeBank, status, typeId);
        List<DocumentDTO> list = docs.stream().map(document ->
                Converter.convertFromDocumentToDTO(document)).collect(Collectors.toList());
        return list;
    }

    @GetMapping("/send")
    public ModelAndView sendFilePage(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        model.addObject("user", user);
        model.setViewName("bank/sendPay");
        return model;
    }

    @RequestMapping(value = "/downloadZip", method = RequestMethod.GET)
    public void downloadFile(@RequestParam("files[]") List<Integer> files, HttpServletResponse response) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        List<String> fileNames = new ArrayList<>();
        for (Integer uuid : files) {
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
            log.info("Банк " + user.getUsername() + " загрузил архив подготовленных документов ");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Банк " + user.getUsername() + " ошибка загрузки архива документов ");
        }
    }

    private File getFile(String filepath) throws FileNotFoundException {
        File file = new File(filepath);
        if (!file.exists()) {
            throw new FileNotFoundException("file with path: " + filepath + " was not found.");
        }
        return file;
    }


    @PostMapping("/errorSign")
    public void errorSign(@RequestParam("codeMes") int codeMes, @RequestParam("nameFile") String nameFile) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        switch (codeMes) {
            case 1:
                log.info("Банк " + user.getUsername() + " успешно проверил подпись документа: " + nameFile);
                break;
            case 0:
                log.error("Банк " + user.getUsername() + " ошибка проверки подписи документа: " + nameFile);
                break;

        }
    }

    @GetMapping("/Beta")
    public ModelAndView beta(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        int docLoad = user.getStatuses().size();
        model.addObject("docLoad", docLoad);
        model.addObject("user", userDetails);
        model.setViewName("bank/indexBeta");
        return model;
    }


//    upload FILE

    @GetMapping("/getUploadFileReturn")
    public @ResponseBody
    List<DocumentDTO> getUploadFilesBank() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
//        SimpleDateFormat sd = new SimpleDateFormat("yyyy.MM.dd");
        List<DocumentDTO> documents = new ArrayList<>();
        List<Document> uploadToday = user.getDocuments().stream()
                .filter(document -> (document.getStatuses().get(document.getStatuses().size() - 1).getStatus().getId() == 1) &&
                                (document.getType().getType_id() == 2)
//                        && sd.format(document.getDateUpload()).equals(sd.format(new Date()))
                )
                .collect(Collectors.toList());
        for (Document document : uploadToday) {
            DocumentDTO documentDTO = Converter.convertFromDocumentToDTO(document);
            documents.add(documentDTO);
        }
        return documents;
    }


    @PostMapping("/uploadSgn")
    public @ResponseBody
    String uploadSgn(@RequestParam("file") MultipartFile file, @RequestParam("path") String pathFile, @RequestParam("typeId") Integer typeId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        byte[] bytes = new byte[0];
        try {
            bytes = file.getBytes();
            String fileName ="";
            switch (typeId){
                case 2: fileName = pathFile.substring(0, 29);break;
                case 3: fileName = pathFile.substring(0, 12);break;
            }
            String pathFileEnd = createDirectory(fileName, typeId);
            Path path = Paths.get(pathFileEnd + "/" + pathFile);
            Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pathFileEnd + "/" + pathFile), "UTF-8"));
            try {
                out.write(Arrays.toString(bytes));
            } finally {
                out.close();
            }
            Files.write(path, bytes);
            log.info(user.getUsername() + " успешно подписал документ: " + pathFile);
            return "SUCCESS";
        } catch (IOException e) {
            e.printStackTrace();
            log.error(user.getUsername() + " НЕУСПЕШНО подписал документ: " + pathFile);
            return e.getMessage();
        }

    }


    @PostMapping("/loadDocument")
    public @ResponseBody
    String fileUpload(@RequestParam("file") MultipartFile[] files, @RequestParam("typeId") Integer typeId) {
        String newPAth = pathToUpload;
        String newPAthClaim = pathToUpload;
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
                String codeUprStr = "";
                Document doc = new Document();
                nameFile = arrayPath[arrayPath.length - 1];
                String pathFileEnd = createDirectory(nameFile, typeId);
                switch (typeId) {
                    case 2:
                        codeBankStr = nameFile.substring(4, 15);
                        codeUprStr = nameFile.substring(0, 3);
                        break;
                    case 3:
                        codeBankStr = user.getOrgForBank().getBic();
                        codeUprStr = "3";
                        break;
                }
                doc.setName(nameFile);
                doc.setPath(pathFileEnd + "/" + nameFile);
                TypeDocument type = new TypeDocument();
                type.setType_id(typeId);
                doc.setType(type); // 2 or 3
                doc.setUser(user);
                doc.setOrgcode(codeBankStr);
                doc.setDateUpload(new Date());
                doc.setOrgcode2(Integer.parseInt(codeUprStr));
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
                log.info(user.getUsername() + " успешно загрузил документ: " + nameFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Ошибка загрузки документа " + user.getUsername() + ", " + "документ: " + nameFile + " Ошибка: " + e.getMessage());
            return e.getMessage();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Ошибка загрузки документа " + user.getUsername() + ", " + "документ: " + nameFile + " Ошибка: " + e.getMessage());
            return e.getMessage();
        }
        return "OK";
    }

    public String createDirectory(String nameFile, Integer typeId) throws IOException {
        String pathFileEnd = "";
        if (typeId == 2) {
            String newPAth = pathToUpload;
            String month = nameFile.substring(22, 24);
            String year = "20" + nameFile.substring(24, 26);
            String period = nameFile.substring(28, 29);
            pathFileEnd = newPAth + year + "_" + month + "_" + period;
        } else if (typeId == 3) {
            String newPAth = getPathToUploadClaim;
            String day = nameFile.substring(4, 6);
            String month = nameFile.substring(6, 8);
            String year = nameFile.substring(8, 12);
            pathFileEnd = newPAth + year + "_" + month + "_" + day;
        }
        Path path = Paths.get(pathFileEnd);
        try {
            if (!Files.exists(Paths.get(pathFileEnd))) {
                Files.createDirectory(Paths.get(pathFileEnd));
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Ошибка создания директории документа у банка " + "документ: " + nameFile + " Ошибка: " + e.getMessage());
            return e.getMessage();
        }
        return pathFileEnd;
    }


    ///////////////CLAIM////////

    @GetMapping("/sendClaim")
    public ModelAndView sendClaimFilePage(Authentication authentication, ModelAndView model) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        model.addObject("user", user);
        model.setViewName("bank/sendClaim");
        return model;
    }

    @GetMapping("/getUploadFileClaimReturn")
    public @ResponseBody
    List<DocumentDTO> getUploadFilesClaimBank() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userService.findByUsername(userDetails.getUsername());
        SimpleDateFormat sd = new SimpleDateFormat("yyyy.MM.dd");
        List<DocumentDTO> documents = new ArrayList<>();
        List<Document> uploadToday = user.getDocuments().stream()
                .filter(document -> (document.getStatuses().get(document.getStatuses().size() - 1).getStatus().getId() == 1) &&
                        (document.getType().getType_id() == 3)
                        && sd.format(document.getDateUpload()).equals(sd.format(new Date()))
                )
                .collect(Collectors.toList());
        for (Document document : uploadToday) {
            DocumentDTO documentDTO = Converter.convertFromDocumentToDTO(document);
            documents.add(documentDTO);
        }
        return documents;
    }


}
